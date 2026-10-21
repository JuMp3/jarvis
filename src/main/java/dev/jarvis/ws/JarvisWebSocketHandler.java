package dev.jarvis.ws;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
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
import dev.jarvis.llm.ProviderRouter;
import dev.jarvis.JarvisApplication;
import dev.jarvis.mcp.McpTools;
import dev.jarvis.settings.SettingsService;
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

    public JarvisWebSocketHandler(SessionRegistry sessions, TurnService turns, ApprovalService approvals,
                                  ProviderRouter router, VoiceServices voice, ChatMemory memory,
                                  TaskService tasks, SettingsService settings, McpTools mcp, JsonMapper json) {
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
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        SessionState state = sessions.register(socket, conversationIdOf(session), router.defaultProvider());
        sessions.send(state.id(), Protocol.hello(router.availableIds(), router.defaultProvider().id(),
                voice.sttMode(), voice.ttsMode(), voice.language(), voice.wakeWord(), settings.setupRequired()));
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
            sessions.send(state.id(), Protocol.error("Messaggio non valido"));
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
            case "cancel" -> {
                state.cancelCurrentTurn();
                sessions.send(state.id(), Protocol.state("idle"));
            }
            case "approval" -> approvals.resolve(state.conversationId(), msg.id(), Boolean.TRUE.equals(msg.approved()));
            case "cancel-task" -> tasks.cancel(state.conversationId(), msg.id());
            case "settings-get" -> sessions.send(state.id(), Protocol.settings(settings.view(), settings.setupRequired()));
            case "settings-save" -> workers.submit(() -> saveSettings(state, msg));
            case "settings-reveal" -> {
                try {
                    sessions.send(state.id(), Protocol.settingsSecret(msg.name(), settings.reveal(msg.name())));
                } catch (IllegalArgumentException e) {
                    sessions.send(state.id(), Protocol.settingsError(e.getMessage()));
                }
            }
            case "settings-test" -> workers.submit(() -> sessions.send(state.id(),
                    Protocol.settingsTestResult(msg.name(), settings.test(msg.name(), msg.value()))));
            case "account-login" -> workers.submit(() -> sessions.send(state.id(),
                    Protocol.accountResult(msg.server(), mcp.adminCall(msg.server(), "login"))));
            case "account-verify" -> workers.submit(() -> sessions.send(state.id(),
                    Protocol.accountResult(msg.server(), mcp.adminCall(msg.server(), "verify-login"))));
            default -> sessions.send(state.id(), Protocol.error("Tipo di messaggio sconosciuto: " + type));
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
            sessions.send(state.id(), Protocol.settingsError("Non riesco a salvare le impostazioni: " + e.getMessage()));
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
            sessions.send(state.id(), Protocol.error("La trascrizione sul server non è attiva: controlla jarvis.voice.stt"));
            return;
        }
        byte[] audio = toBytes(message.getPayload());
        workers.submit(() -> {
            state.cancelCurrentTurn();
            sessions.send(state.id(), Protocol.state("thinking"));
            try {
                String text = stt.transcribe(audio, AudioFormats.detectMime(audio));
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
            } catch (RuntimeException e) {
                log.warn("Transcription failed for session {}", state.id(), e);
                sessions.send(state.id(), Protocol.error(ErrorMessages.forVoice(e)));
                sessions.send(state.id(), Protocol.done());
            }
        });
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

    /** The client keeps a conversation id across reconnects (?conversation=...). */
    private static String conversationIdOf(WebSocketSession session) {
        if (session.getUri() != null) {
            String requested = UriComponentsBuilder.fromUri(session.getUri()).build()
                    .getQueryParams().getFirst("conversation");
            if (requested != null && CONVERSATION_ID.matcher(requested).matches()) {
                return requested;
            }
        }
        return session.getId();
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
