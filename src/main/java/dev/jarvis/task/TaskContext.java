package dev.jarvis.task;

/**
 * What a running specialist may do besides answering: report what it is doing and ask the user.
 */
public interface TaskContext {

    /** Shown live in the Squadra panel, e.g. "Modifica App.java". */
    void progress(String message);

    /**
     * Shows the task as waiting and blocks until the user answers; the task timeout is paused meanwhile.
     *
     * @return true only if the user explicitly approved
     */
    boolean requestApproval(String description);
}
