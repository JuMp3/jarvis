package dev.jarvis.task;

/**
 * What a running specialist may do besides answering: report what it is doing and ask the user.
 */
public interface TaskContext {

    /** Shown live in the Squadra panel, e.g. "Modifica App.java". */
    void progress(String message);

    /** The phase reached, when the specialist knows how many there are: the page shows it as a ring. */
    default void step(int step, int steps) {
    }

    /**
     * Shows the task as waiting and blocks until the user answers; the task timeout is paused meanwhile.
     *
     * @return true only if the user explicitly approved
     */
    boolean requestApproval(String description);

    /**
     * Gives part of the work to another specialist and waits for it: a task of its own under this one, in the Squadra
     * panel. Only one level: a delegated part cannot delegate again.
     *
     * @return its result, or why there is none, for the model
     */
    default String delegate(String agent, String title, String instructions, String expectedOutput) {
        return "Qui non puoi delegare.";
    }
}
