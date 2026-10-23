package dev.jarvis.ws;

import static dev.jarvis.i18n.Text.text;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;

import dev.jarvis.agent.ApprovalService;
import dev.jarvis.agent.PostgresChatMemoryRepository;
import dev.jarvis.llm.ProviderRouter;
import dev.jarvis.JarvisApplication;
import dev.jarvis.mcp.McpTools;
import dev.jarvis.settings.SettingsService;
import dev.jarvis.task.AgentTask;
import dev.jarvis.task.TaskService;
import dev.jarvis.voice.AudioFormats;
import dev.jarvis.voice.SpeechToText;
import dev.jarvis.voice.VoiceServices;
import dev.jarvis.voice.WakeWord;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point for the browser. Text frames carry JSON protocol messages, binary frames carry
 * recorded speech. Work is handed to virtual threads so the socket thread never blocks.
 */
@Component
public class JarvisWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JarvisWebSocketHandler.class);
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT = 16 * 1024 * 1024;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final SessionRegistry sessions;
    private final TurnService turns;
    private final ApprovalService approvals;
    private final ProviderRouter router;
    private final VoiceServices voice;
    private final ChatMemory memory;
    private final TaskService tasks;
    private final SettingsService settings;
    private final McpTools mcp;
    private final JsonMapper json;
    private final PostgresChatMemoryRepository archive;

    public JarvisWebSocketHandler(SessionRegistry sessions, TurnService turns, ApprovalService approvals,
                                  ProviderRouter router, VoiceServices voice, ChatMemory memory,
                                  TaskService tasks, SettingsService settings, McpTools mcp, JsonMapper json,
                                  PostgresChatMemoryRepository archive) {
        this.sessions = sessions;
        this.turns = turns;
        this.approvals = approvals;
        this.router = router;
        this.voice = voice;
        this.memory = memory;
        this.tasks = tasks;
        this.settings = settings;
        this.mcp = mcp;
        this.json = json;
        this.archive = archive;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        SessionState state = sessions.register(socket, conversationIdOf(session), router.defaultProvider());
        sessions.send(state.id(), Protocol.hello(router.availableIds(), router.defaultProvider().id(),
                voice.sttMode(), voice.ttsMode(), voice.language(), voice.wakeWord(), settings.setupRequired(),
                state.conversationId(), settings.theme()));
        sessions.send(state.id(), Protocol.history(historyOf(state.conversationId())));
        sessions.send(state.id(), Protocol.tasks(tasks.recent(state.conversationId())));
        // Tasks may have asked for approval while no tab was open
        approvals.pendingTaskRequests(state.conversationId())
                .forEach(request -> sessions.send(state.id(), Protocol.approvalRequest(request)));
    }

    /** What the user saw in the conversation: their messages and Jarvis' text answers, no tool traffic. */
    private List<Protocol.HistoryEntry> historyOf(String conversationId) {
        return memory.get(conversationId).stream()
                .filter(message -> message.getText() != null && !message.getText().isBlank())
                .filter(message -> !message.getText().startsWith(TaskNotifier.NOTICE_PREFIX))
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .map(message -> new Protocol.HistoryEntry(
                        message.getMessageType() == MessageType.USER ? "user" : "assistant", message.getText()))
                .toList();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = sessions.get(session.getId()).orElse(null);
        if (state == null) {
            return;
        }
        Protocol.ClientMessage msg;
        try {
            msg = json.readValue(message.getPayload(), Protocol.ClientMessage.class);
        } catch (RuntimeException e) {
            sessions.send(state.id(), Protocol.error(text("Messaggio non valido", "Invalid message").in(voice.language())));
            return;
        }
        String type = msg.type() == null ? "" : msg.type();
        switch (type) {
            case "chat" -> {
                if (msg.text() == null || msg.text().isBlank()) {
                    return;
                }
                if (msg.provider() != null) {
                    state.provider(router.resolve(msg.provider()));
                }
                // Spoken turn: server audio only if tts runs on the server, otherwise the browser speaks the tokens
                boolean speak = Boolean.TRUE.equals(msg.speak());
                workers.submit(() -> turns.start(state, msg.text().trim(), speak));
            }
            case "set-provider" -> state.provider(router.resolve(msg.provider()));
            case "voice-mode" -> state.wakeWordRequired(Boolean.TRUE.equals(msg.wake()));
            // A phrase sent in pieces (binary frames in between): the user may be interrupting Jarvis
            case "speech-start" -> {
                state.cancelCurrentTurn();
                state.utterance(new Utterance(workers));
            }
            case "speech-end" -> endUtterance(state);
            case "cancel" -> {
                state.cancelCurrentTurn();
                sessions.send(state.id(), Protocol.state("idle"));
            }
            case "approval" -> approvals.resolve(state.conversationId(), msg.id(), Boolean.TRUE.equals(msg.approved()));
            case "cancel-task" -> tasks.cancel(state.conversationId(), msg.id());
            case "clear-history" -> clearHistory(state);
            case "clear-tasks" -> {
                tasks.clearFinished(state.conversationId());
                List<AgentTask> remaining = tasks.recent(state.conversationId());
                sessions.ofConversation(state.conversationId())
                        .forEach(tab -> sessions.send(tab.id(), Protocol.tasks(remaining)));
            }
            // value: a language just chosen in the wizard, to show the rest in it
            case "settings-get" -> sessions.send(state.id(),
                    Protocol.settings(settings.view(msg.value()), settings.setupRequired()));
            case "settings-save" -> workers.submit(() -> saveSettings(state, msg));
            case "settings-reveal" -> {
                try {
                    sessions.send(state.id(), Protocol.settingsSecret(msg.name(), settings.reveal(msg.name())));
                } catch (IllegalArgumentException e) {
                    sessions.send(state.id(), Protocol.settingsError(e.getMessage()));
                }
            }
            case "settings-test" -> workers.submit(() -> sessions.send(state.id(),
                    Protocol.settingsTestResult(msg.name(), settings.test(msg.name(), msg.value(), msg.values()))));
            case "settings-preview" -> workers.submit(() -> preview(state, msg));
            case "account-login" -> workers.submit(() -> sessions.send(state.id(),
                    Protocol.accountResult(msg.server(), mcp.adminCall(msg.server(), "login"))));
            case "account-verify" -> workers.submit(() -> sessions.send(state.id(),
                    Protocol.accountResult(msg.server(), mcp.adminCall(msg.server(), "verify-login"))));
            default -> sessions.send(state.id(), Protocol.error(text("Tipo di messaggio sconosciuto: %s",
                    "Unknown message type: %s").in(voice.language(), type)));
        }
    }

    /**
     * Forgets the conversation, in every open tab. Not during a turn: closing an interrupted turn writes to the
     * memory, and could do it right after the deletion. Running tasks go on and report to the empty conversation.
     */
    private void clearHistory(SessionState state) {
        List<SessionState> tabs = sessions.ofConversation(state.conversationId());
        if (!tabs.stream().allMatch(SessionState::idle)) {
            sessions.send(state.id(), Protocol.error(text("Aspetta che Jarvis finisca di rispondere, poi riprova.",
                    "Wait for Jarvis to finish answering, then try again.").in(voice.language())));
            return;
        }
        memory.clear(state.conversationId());
        tabs.forEach(tab -> sessions.send(tab.id(), Protocol.historyCleared()));
    }

    /** A sample of a voice, before it is chosen and saved. */
    private void preview(SessionState state, Protocol.ClientMessage msg) {
        try {
            var audio = settings.preview(msg.name(), msg.value(), msg.values());
            sessions.send(state.id(), Protocol.settingsPreview(msg.name(), msg.value(), audio.mimeType(),
                    java.util.Base64.getEncoder().encodeToString(audio.data()), null));
        } catch (IllegalArgumentException e) {
            sessions.send(state.id(), Protocol.settingsPreview(msg.name(), msg.value(), null, null, e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Voice preview failed", e);
            sessions.send(state.id(), Protocol.settingsPreview(msg.name(), msg.value(), null, null,
                    ErrorMessages.forVoice(voice.language(), e)));
        }
    }

    /** Saved settings take effect with a restart of the application; the pages reconnect by themselves. */
    private void saveSettings(SessionState state, Protocol.ClientMessage msg) {
        try {
            settings.save(msg.values() == null ? Map.of() : msg.values(), msg.cleared() == null ? List.of() : msg.cleared());
        } catch (IllegalArgumentException e) {
            sessions.send(state.id(), Protocol.settingsError(e.getMessage()));
            return;
        } catch (RuntimeException e) {
            log.warn("Cannot save settings", e);
            sessions.send(state.id(), Protocol.settingsError(text("Non riesco a salvare le impostazioni: %s",
                    "I can't save the settings: %s").in(voice.language(), e.getMessage())));
            return;
        }
        sessions.send(state.id(), Protocol.settingsSaved(true));
        JarvisApplication.restart();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionState state = sessions.get(session.getId()).orElse(null);
        if (state == null) {
            return;
        }
        SpeechToText stt = voice.speechToText().orElse(null);
        if (stt == null) {
            sessions.send(state.id(), Protocol.error(text("La trascrizione sul server non è attiva: controlla Impostazioni → Voce.",
                    "Transcription on the server is off: check Settings → Voice.").in(voice.language())));
            return;
        }
        byte[] audio = toBytes(message.getPayload());
        String mimeType = AudioFormats.detectMime(audio);
        Utterance utterance = state.utterance();
        if (utterance != null) {
            // A piece of a phrase still being spoken: transcribed now, the phrase goes on at speech-end
            long startedAt = System.nanoTime();
            utterance.add(before -> {
                String text = Utterance.join(before, stt.transcribe(audio, mimeType, before));
                log.debug("Piece transcribed in {} ms", (System.nanoTime() - startedAt) / 1_000_000);
                if (state.utterance() == utterance) {
                    sessions.send(state.id(), Protocol.transcriptPartial(text));
                }
                return text;
            });
            return;
        }
        workers.submit(() -> {
            state.cancelCurrentTurn();
            sessions.send(state.id(), Protocol.state("thinking"));
            try {
                heard(state, stt.transcribe(audio, mimeType));
            } catch (RuntimeException e) {
                transcriptionFailed(state, e);
            }
        });
    }

    /** The phrase is over: what is left is the last piece, then the turn starts with the whole of it. */
    private void endUtterance(SessionState state) {
        Utterance utterance = state.utterance();
        if (utterance == null) {
            return;
        }
        state.utterance(null);
        sessions.send(state.id(), Protocol.state("thinking"));
        utterance.text().whenCompleteAsync((text, error) -> {
            if (error != null) {
                transcriptionFailed(state, error);
            } else {
                heard(state, text);
            }
        }, workers);
    }

    /** What the user said: to the model, unless hands-free requires the wake word and it is not there. */
    private void heard(SessionState state, String transcript) {
        String text = transcript;
        if (state.wakeWordRequired() && !text.isBlank()) {
            var request = WakeWord.strip(text, voice.wakeWord());
            if (request.isEmpty()) {
                sessions.send(state.id(), Protocol.transcriptIgnored(text));
                sessions.send(state.id(), Protocol.done());
                return;
            }
            text = request.get().isBlank() ? text : request.get(); // "Jarvis?" alone: let it answer
        }
        sessions.send(state.id(), Protocol.transcript(text));
        if (text.isBlank()) {
            sessions.send(state.id(), Protocol.done());
            return;
        }
        turns.start(state, text, true);
    }

    private void transcriptionFailed(SessionState state, Throwable error) {
        log.warn("Transcription failed for session {}", state.id(), error);
        sessions.send(state.id(), Protocol.error(ErrorMessages.forVoice(voice.language(), error)));
        sessions.send(state.id(), Protocol.done());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on session {}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.get(session.getId()).ifPresent(SessionState::cancelCurrentTurn);
        approvals.denyAll(session.getId());
        sessions.unregister(session.getId());
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
    }

    /**
     * The client keeps a conversation id across reconnects (?conversation=...). A page without one, or with one that
     * never got a message (a new browser, the desktop window), resumes the latest conversation: one user, one
     * ongoing conversation. A cleared conversation keeps its messages in the archive, so it stays the same.
     */
    private String conversationIdOf(WebSocketSession session) {
        if (session.getUri() != null) {
            String requested = UriComponentsBuilder.fromUri(session.getUri()).build()
                    .getQueryParams().getFirst("conversation");
            if (requested != null && CONVERSATION_ID.matcher(requested).matches() && archive.hasMessages(requested)) {
                return requested;
            }
        }
        return archive.latestConversationId().orElseGet(() -> UUID.randomUUID().toString());
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
