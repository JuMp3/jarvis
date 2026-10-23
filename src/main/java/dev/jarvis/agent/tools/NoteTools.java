package dev.jarvis.agent.tools;

import static dev.jarvis.i18n.Text.text;

import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;

/**
 * Example of a tool with side effects: it writes only inside the sandbox and only after approval.
 * Use it as the template for every future tool that writes, sends, deletes or executes.
 */
@Component
public class NoteTools {

    private static final int PREVIEW_LENGTH = 200;

    private final ApprovalService approvals;
    private final NoteStore store;
    private final String language;

    public NoteTools(ApprovalService approvals, JarvisProperties properties) {
        this.approvals = approvals;
        this.language = properties.language();
        this.store = new NoteStore(Path.of(properties.agent().notesDir()));
    }

    @Tool(description = "Salva una nota in markdown nella cartella delle note di Jarvis. "
            + "L'utente deve approvare il salvataggio.")
    public String saveNote(@ToolParam(description = "Titolo breve della nota") String title,
                           @ToolParam(description = "Contenuto della nota in markdown") String content,
                           ToolContext toolContext) {
        Path target = store.pathFor(title);
        String description = text("Salvare la nota \"%s\":\n%s", "Save the note \"%s\":\n%s")
                .in(language, target.getFileName(), preview(content));

        if (!approvals.requestApproval(toolContext, description)) {
            return "L'utente non ha approvato. Nessuna nota salvata.";
        }
        store.save(title, content);
        return "Nota salvata come " + target.getFileName();
    }

    @Tool(description = "Elenca i nomi dei file delle note salvate da Jarvis.")
    public List<String> listNotes() {
        return store.list();
    }

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH) + "…";
    }
}
