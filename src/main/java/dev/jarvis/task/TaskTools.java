package dev.jarvis.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.AgentService;

/**
 * Jarvis' tools as team leader: start specialists in the background, check on them, stop them.
 * Only the orchestrator gets these tools; specialists cannot delegate.
 */
@Component
public class TaskTools {

    private static final int RESULT_PREVIEW = 1500;

    private final TaskService tasks;

    public TaskTools(TaskService tasks) {
        this.tasks = tasks;
    }

    @Tool(description = "Avvia un task in background affidato a uno specialista e restituisce subito il suo id. "
            + "Per lavori indipendenti chiamalo più volte: i task girano in parallelo. Quando un task finisce "
            + "ricevi un aggiornamento automatico con il risultato.")
    public String startTask(
            @ToolParam(description = "Id dello specialista, tra quelli elencati nelle istruzioni di sistema") String agent,
            @ToolParam(description = "Titolo breve del task, mostrato all'utente") String title,
            @ToolParam(description = "Istruzioni complete e autosufficienti: lo specialista non vede la conversazione") String instructions,
            @ToolParam(description = "Cosa deve restituire lo specialista (formato, lunghezza)", required = false) String expectedOutput,
            ToolContext toolContext) {
        try {
            AgentTask task = tasks.start(conversationId(toolContext), agent, title, instructions, expectedOutput);
            return "Task avviato: id " + task.id() + ", specialista " + agent + ".";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Tool(description = "Elenca i task di questa conversazione con stato, durata e, se finiti, l'inizio del risultato.")
    public String listTasks(ToolContext toolContext) {
        List<AgentTask> recent = tasks.recent(conversationId(toolContext));
        if (recent.isEmpty()) {
            return "Nessun task in questa conversazione.";
        }
        return recent.stream().map(task -> describe(task, RESULT_PREVIEW)).collect(Collectors.joining("\n\n"));
    }

    @Tool(description = "Ferma un task in coda o in esecuzione. Usa \"all\" per fermarli tutti.")
    public String cancelTask(@ToolParam(description = "Id del task, oppure \"all\"") String taskId,
                             ToolContext toolContext) {
        String conversationId = conversationId(toolContext);
        if ("all".equalsIgnoreCase(taskId)) {
            return "Task fermati: " + tasks.cancelAll(conversationId) + ".";
        }
        return tasks.cancel(conversationId, taskId) ? "Task fermato." : "Nessun task attivo con id " + taskId + ".";
    }

    private static String conversationId(ToolContext toolContext) {
        return (String) toolContext.getContext().get(AgentService.CONVERSATION_ID_KEY);
    }

    /** @param resultLimit characters of the result to include */
    public static String describe(AgentTask task, int resultLimit) {
        StringBuilder text = new StringBuilder()
                .append("- id ").append(task.id()).append(" | ").append(task.agent()).append(" | ")
                .append(task.title()).append(" | ").append(task.status());
        if (task.startedAt() != null) {
            Instant end = task.finishedAt() != null ? task.finishedAt() : Instant.now();
            text.append(" | ").append(Duration.between(task.startedAt(), end).toSeconds()).append(" s");
        }
        if (task.result() != null) {
            String result = task.result();
            text.append("\n  Risultato: ").append(result.length() <= resultLimit ? result
                    : result.substring(0, resultLimit) + "…");
        }
        if (task.error() != null) {
            text.append("\n  Motivo: ").append(task.error());
        }
        return text.toString();
    }
}
