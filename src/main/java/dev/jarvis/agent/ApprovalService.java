package dev.jarvis.agent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Human-in-the-loop gate for tools with side effects.
 *
 * <p>A tool calls {@link #requestApproval} and blocks until the user answers, the timeout expires,
 * or the session's turn is cancelled. Anything other than an explicit "yes" counts as denied.
 * Blocking is cheap because tools run on virtual threads.
 */
public class ApprovalService {

    private record Pending(String sessionId, CompletableFuture<Boolean> answer) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ApprovalChannel channel;
    private final Duration timeout;

    public ApprovalService(ApprovalChannel channel, Duration timeout) {
        this.channel = channel;
        this.timeout = timeout;
    }

    /**
     * Asks the user of {@code sessionId} to approve an action.
     *
     * @return true only if the user explicitly approved
     */
    public boolean requestApproval(String sessionId, String description) {
        if (sessionId == null) {
            return false;
        }
        String id = UUID.randomUUID().toString();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        pending.put(id, new Pending(sessionId, answer));
        try {
            channel.send(new ApprovalRequest(id, sessionId, description));
            return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            return false;
        } finally {
            pending.remove(id);
        }
    }

    /**
     * Records the user's answer. Only the session the request was sent to may answer it.
     *
     * @return false if the request is unknown, already answered or belongs to another session
     */
    public boolean resolve(String sessionId, String requestId, boolean approved) {
        Pending entry = requestId == null ? null : pending.get(requestId);
        if (entry == null || !entry.sessionId().equals(sessionId)) {
            return false;
        }
        return entry.answer().complete(approved);
    }

    /** Denies every open request of a session (turn cancelled or connection closed). */
    public void denyAll(String sessionId) {
        pending.values().stream()
                .filter(entry -> entry.sessionId().equals(sessionId))
                .forEach(entry -> entry.answer().complete(false));
    }

    int pendingCount() {
        return pending.size();
    }
}
