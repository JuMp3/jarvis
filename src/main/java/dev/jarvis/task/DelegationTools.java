package dev.jarvis.task;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * For a specialist allowed to delegate (delegates in its configuration): ask another one for a part of the work and
 * wait for it. Which ones it may ask is in its system prompt; the task runs it (TaskContext.delegate).
 */
final class DelegationTools {

    /** The tool context's key for the TaskContext of the running task. */
    static final String TASK_KEY = "jarvis.task";

    @Tool(description = "Chiede a un altro specialista una parte del lavoro e ne attende il risultato. Usalo solo per "
            + "ciò che lui sa fare meglio (per esempio una ricerca sul web); le istruzioni devono essere complete.")
    public String askSpecialist(@ToolParam(description = "Id dello specialista, tra quelli elencati nelle istruzioni") String agent,
                                @ToolParam(description = "Titolo breve del sotto-lavoro") String title,
                                @ToolParam(description = "Istruzioni complete e autosufficienti") String instructions,
                                @ToolParam(description = "Cosa deve restituire", required = false) String expectedOutput,
                                ToolContext toolContext) {
        Object context = toolContext == null ? null : toolContext.getContext().get(TASK_KEY);
        return context instanceof TaskContext task ? task.delegate(agent, title, instructions, expectedOutput)
                : "Qui non puoi delegare.";
    }
}
