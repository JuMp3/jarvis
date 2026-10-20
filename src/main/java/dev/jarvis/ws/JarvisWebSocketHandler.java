package dev.jarvis.ws;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import dev.jarvis.voice.AudioFormats;
import dev.jarvis.voice.SpeechToText;
import dev.jarvis.voice.VoiceServices;
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
    private final JsonMapper json;

    public JarvisWebSocketHandler(SessionRegistry sessions, TurnService turns, ApprovalService approvals,
                                  ProviderRouter router, VoiceServices voice, JsonMapper json) {
        this.sessions = sessions;
        this.turns = turns;
        this.approvals = approvals;
        this.router = router;
        this.voice = voice;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        SessionState state = sessions.register(socket, conversationIdOf(session), router.defaultProvider());
        sessions.send(state.id(), Protocol.hello(router.availableIds(), router.defaultProvider().id(), voice.enabled()));
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
                boolean speak = Boolean.TRUE.equals(msg.speak()) && voice.enabled();
                workers.submit(() -> turns.start(state, msg.text().trim(), speak));
            }
            case "set-provider" -> state.provider(router.resolve(msg.provider()));
            case "cancel" -> {
                state.cancelCurrentTurn();
                sessions.send(state.id(), Protocol.state("idle"));
            }
            case "approval" -> approvals.resolve(state.id(), msg.id(), Boolean.TRUE.equals(msg.approved()));
            default -> sessions.send(state.id(), Protocol.error("Tipo di messaggio sconosciuto: " + type));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionState state = sessions.get(session.getId()).orElse(null);
        if (state == null) {
            return;
        }
        SpeechToText stt = voice.speechToText().orElse(null);
        if (stt == null) {
            sessions.send(state.id(), Protocol.error("Voce non configurata: imposta jarvis.voice.openai-api-key"));
            return;
        }
        byte[] audio = toBytes(message.getPayload());
        workers.submit(() -> {
            state.cancelCurrentTurn();
            sessions.send(state.id(), Protocol.state("thinking"));
            try {
                String text = stt.transcribe(audio, AudioFormats.detectMime(audio));
                sessions.send(state.id(), Protocol.transcript(text));
                if (text.isBlank()) {
                    sessions.send(state.id(), Protocol.done());
                    return;
                }
                turns.start(state, text, true);
            } catch (RuntimeException e) {
                log.warn("Transcription failed for session {}", state.id(), e);
                sessions.send(state.id(), Protocol.error("Trascrizione non riuscita: " + e.getMessage()));
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
