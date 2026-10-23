package dev.jarvis.ws;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.jarvis.task.AgentTask;
import dev.jarvis.task.TaskService.TaskUpdated;
import dev.jarvis.task.TaskTools;

/**
 * Forwards task changes to the user's open tabs and, when a task ends, hands the outcome to Jarvis as an
 * automatic message so it can report it, by voice if the last turn was spoken.
 */
@Component
public class TaskNotifier {

    /** Marks automatic messages in memory: never shown as something the user wrote. */
    static final String NOTICE_PREFIX = "[Aggiornamento automatico sui task, non scritto dall'utente]";
    private static final int NOTICE_RESULT_LIMIT = 6000;

    private final SessionRegistry sessions;
    private final TurnService turns;

    public TaskNotifier(SessionRegistry sessions, TurnService turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    @EventListener
    public void onTaskUpdated(TaskUpdated event) {
        AgentTask task = event.task();
        List<SessionState> open = sessions.ofConversation(task.conversationId());
        open.forEach(state -> sessions.send(state.id(), Protocol.taskUpdate(task)));
        // Cancelled by the user: nothing to report. Without an open tab the result stays in the panel and listTasks.
        // A delegated part goes to the specialist that asked for it, not to Jarvis.
        if (task.status().terminal() && task.status() != AgentTask.Status.CANCELLED && !open.isEmpty()
                && task.parentId() == null) {
            turns.notify(open.getLast(), notice(task));
        }
    }

    static String notice(AgentTask task) {
        return NOTICE_PREFIX + "\n" + TaskTools.describe(task, NOTICE_RESULT_LIMIT)
                + "\nRiferisci all'utente in poche frasi cosa è emerso. Non ripetere tutto: il risultato completo "
                + "è nel pannello Squadra. Se altri task sono ancora in corso, dillo.";
    }
}
