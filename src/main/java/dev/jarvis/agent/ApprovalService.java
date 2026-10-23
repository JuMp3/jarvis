package dev.jarvis.agent;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Human-in-the-loop gate for actions with side effects.
 *
 * <p>The caller blocks until the user answers, the timeout expires, or the request is withdrawn (Jarvis'
 * turn cancelled, task stopped). Anything other than an explicit "yes" counts as denied. Blocking is cheap
 * because tools and tasks run on virtual threads.
 *
 * <p>Requests belong to a conversation, not to a connection: any open tab of the conversation may answer,
 * and requests of background tasks are shown again after a reconnection.
 */
public class ApprovalService {

    private record Pending(ApprovalRequest request, CompletableFuture<Boolean> answer, long createdAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ApprovalChannel channel;
    private final Duration turnTimeout;
    private final Duration taskTimeout;

    public ApprovalService(ApprovalChannel channel, Duration turnTimeout, Duration taskTimeout) {
        this.channel = channel;
        this.turnTimeout = turnTimeout;
        this.taskTimeout = taskTimeout;
    }

    /**
     * In a tool context, a {@code Predicate<String>} that asks instead of Jarvis' turn: the background task the tool
     * runs in (a routine), whose request waits like any task's.
     */
    public static final String APPROVER_KEY = "jarvis.approver";

    /**
     * Asks for the action of a tool: through the task it runs in, if any, else for Jarvis' own turn.
     *
     * @return true only if the user explicitly approved
     */
    @SuppressWarnings("unchecked")
    public boolean requestApproval(ToolContext toolContext, String description) {
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        if (context.get(APPROVER_KEY) instanceof Predicate<?> approver) {
            return ((Predicate<String>) approver).test(description);
        }
        return requestApproval((String) context.get(AgentService.CONVERSATION_ID_KEY),
                (String) context.get(AgentService.SESSION_ID_KEY), description);
    }

    /**
     * Asks for an action of Jarvis' own turn; denied as soon as that turn ends.
     *
     * @return true only if the user explicitly approved
     */
    public boolean requestApproval(String conversationId, String sessionId, String description) {
        if (conversationId == null || sessionId == null) {
            return false;
        }
        return await(new ApprovalRequest(UUID.randomUUID().toString(), conversationId, sessionId, null, null,
                description), turnTimeout);
    }

    /**
     * Asks for an action of a background task; waits up to the task approval timeout, across reconnections.
     *
     * @return true only if the user explicitly approved
     */
    public boolean requestTaskApproval(String conversationId, String agent, String taskTitle, String description) {
        return await(new ApprovalRequest(UUID.randomUUID().toString(), conversationId, null, agent, taskTitle,
                description), taskTimeout);
    }

    private boolean await(ApprovalRequest request, Duration timeout) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        pending.put(request.id(), new Pending(request, answer, System.nanoTime()));
        try {
            channel.send(request);
            return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            return false;
        } finally {
            pending.remove(request.id());
            channel.closed(request);
        }
    }

    /**
     * Records the user's answer. Only a tab of the request's conversation may answer it.
     *
     * @return false if the request is unknown, already answered or belongs to another conversation
     */
    public boolean resolve(String conversationId, String requestId, boolean approved) {
        Pending entry = requestId == null ? null : pending.get(requestId);
        if (entry == null || !entry.request().conversationId().equals(conversationId)) {
            return false;
        }
        return entry.answer().complete(approved);
    }

    /** Denies the open requests of Jarvis' turn in a session (turn cancelled or connection closed). */
    public void denyAll(String sessionId) {
        pending.values().stream()
                .filter(entry -> sessionId.equals(entry.request().sessionId()))
                .forEach(entry -> entry.answer().complete(false));
    }

    /** Open requests of background tasks, oldest first, to show again to a tab that (re)connects. */
    public List<ApprovalRequest> pendingTaskRequests(String conversationId) {
        return pending.values().stream()
                .filter(entry -> entry.request().fromTask() && entry.request().conversationId().equals(conversationId))
                .sorted(Comparator.comparingLong(Pending::createdAt))
                .map(Pending::request)
                .toList();
    }

    int pendingCount() {
        return pending.size();
    }
}
