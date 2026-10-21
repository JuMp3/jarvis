package dev.jarvis.ws;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import dev.jarvis.agent.ApprovalChannel;
import dev.jarvis.agent.ApprovalRequest;
import dev.jarvis.llm.ProviderId;
import tools.jackson.databind.json.JsonMapper;

/**
 * Open connections and the only place that writes to them.
 */
@Component
public class SessionRegistry implements ApprovalChannel {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public SessionRegistry(JsonMapper json) {
        this.json = json;
    }

    SessionState register(WebSocketSession socket, String conversationId, ProviderId provider) {
        SessionState state = new SessionState(socket, conversationId, provider);
        sessions.put(state.id(), state);
        return state;
    }

    Optional<SessionState> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    /** Sends a protocol message; silently drops it if the session is gone. */
    void send(String sessionId, Object message) {
        SessionState state = sessions.get(sessionId);
        if (state == null || !state.socket().isOpen()) {
            return;
        }
        try {
            state.socket().sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (IOException | RuntimeException e) {
            if (state.socket().isOpen()) {
                log.warn("Cannot send to session {}: {}", sessionId, e.getMessage());
            } else {
                log.debug("Session {} closed while sending: {}", sessionId, e.getMessage());
            }
        }
    }

    @Override
    public void send(ApprovalRequest request) {
        SessionState state = sessions.get(request.sessionId());
        if (state == null || !state.socket().isOpen()) {
            throw new IllegalStateException("Session " + request.sessionId() + " is closed");
        }
        send(request.sessionId(), Protocol.approvalRequest(request.id(), request.description()));
    }
}
