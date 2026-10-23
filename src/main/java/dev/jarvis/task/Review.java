package dev.jarvis.task;

import static dev.jarvis.i18n.Text.text;

import dev.jarvis.i18n.Text;

/**
 * The reviewer's verdict on a specialist's result: the first line is OK, or RIVEDERE followed by what to fix. Pure
 * logic: the tasks the reviewer and the specialist receive, and how their verdict and tokens are read.
 *
 * @param notes what to fix; empty when ok
 */
record Review(boolean ok, String notes) {

    static final Text CHECKING = text("Il revisore controlla il risultato", "The reviewer checks the result");
    static final Text REDOING = text("Rivede il lavoro con le osservazioni del revisore", "Reworks it with the reviewer's notes");

    /** Anything but a clear RIVEDERE counts as OK: a reviewer that fails to answer must not block the work. */
    static Review of(String verdict) {
        java.util.List<String> lines = (verdict == null ? "" : verdict.strip()).lines().toList();
        String first = lines.isEmpty() ? "" : lines.getFirst().strip();
        String keyword = first.toUpperCase(java.util.Locale.ROOT);
        if (!keyword.startsWith("RIVEDERE") && !keyword.startsWith("REVISE")) {
            return new Review(true, "");
        }
        // "RIVEDERE: manca la fonte" on one line, or the notes on the lines below
        String sameLine = first.substring(keyword.startsWith("RIVEDERE") ? 8 : 6).replaceFirst("^[\\s:.-]+", "");
        String notes = (sameLine + "\n" + String.join("\n", lines.subList(1, lines.size()))).strip();
        return new Review(false, notes.isEmpty() ? "Rendilo più completo e preciso." : notes);
    }

    /** What the reviewer is given: the instructions, what was expected, the result. */
    static AgentTask task(AgentTask task, String result) {
        String instructions = "Istruzioni date allo specialista " + task.agent() + ":\n" + task.instructions()
                + (task.expectedOutput() == null || task.expectedOutput().isBlank() ? ""
                        : "\n\nRisultato atteso:\n" + task.expectedOutput())
                + "\n\nRisultato da controllare:\n" + result;
        return AgentTask.queued(task.id(), task.conversationId(), "reviewer", task.title(), instructions, null, null,
                task.parentId());
    }

    /** The same task again, with the first version and what the reviewer asked to fix. */
    static AgentTask redo(AgentTask task, String first, String notes) {
        String instructions = task.instructions() + "\n\nUna prima versione è stata rivista. Osservazioni del revisore, "
                + "da seguire:\n" + notes + "\n\nPrima versione:\n" + first;
        return AgentTask.queued(task.id(), task.conversationId(), task.agent(), task.title(), instructions,
                task.expectedOutput(), task.workspace(), task.parentId());
    }

    /** Tokens of both calls; the text is the one to keep. */
    static Specialists.Result sum(Specialists.Result a, Specialists.Result b, String text) {
        return new Specialists.Result(text, add(a.inputTokens(), b.inputTokens()), add(a.outputTokens(), b.outputTokens()),
                a.costUsd() == null && b.costUsd() == null ? null
                        : (a.costUsd() == null ? 0 : a.costUsd()) + (b.costUsd() == null ? 0 : b.costUsd()));
    }

    private static Integer add(Integer a, Integer b) {
        return a == null && b == null ? null : (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
