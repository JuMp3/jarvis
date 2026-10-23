package dev.jarvis.agent.tools;

import static dev.jarvis.i18n.Text.text;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.AgentService;
import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.DesktopApp;

/**
 * Opens links, folders and the applications of the catalog {@code jarvis.desktop.apps}, always after the
 * user's approval. Commands start without a shell (one argument each), so nothing in a link, a path or a
 * name can inject a command; the catalog, not the model, decides which programs exist.
 */
@Component
public class DesktopTools {

    private static final String TARGET = "{target}";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final ApprovalService approvals;
    private final Map<String, DesktopApp> apps;
    private final String language;

    public DesktopTools(ApprovalService approvals, JarvisProperties properties) {
        this.approvals = approvals;
        this.language = properties.language();
        this.apps = properties.desktop() == null || properties.desktop().apps() == null
                ? Map.of() : properties.desktop().apps();
    }

    @Tool(description = "Apre un link http o https nel browser predefinito. L'utente deve confermare.")
    public String openUrl(@ToolParam(description = "Indirizzo completo, http o https") String url,
                          ToolContext toolContext) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return "Indirizzo non valido: " + url;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            return "Apro solo link http o https.";
        }
        return run(toolContext, text("Aprire nel browser:\n%s", "Open in the browser:\n%s").in(language, uri),
                opener(uri.toString()));
    }

    @Tool(description = "Apre una cartella nel gestore file. L'utente deve confermare.")
    public String openFolder(@ToolParam(description = "Percorso della cartella") String path, ToolContext toolContext) {
        Path folder = Path.of(path.strip()).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            return "La cartella " + folder + " non esiste.";
        }
        return run(toolContext, text("Aprire la cartella:\n%s", "Open the folder:\n%s").in(language, folder),
                opener(folder.toString()));
    }

    @Tool(description = "Avvia un'applicazione del catalogo di Jarvis (vedi listApps), con un file o una cartella "
            + "se l'applicazione lo prevede. L'utente deve confermare.")
    public String launchApp(@ToolParam(description = "Nome dell'applicazione nel catalogo") String name,
                            @ToolParam(description = "File o cartella da aprire, se l'app lo prevede", required = false) String target,
                            ToolContext toolContext) {
        DesktopApp app = apps.get(name == null ? "" : name.strip().toLowerCase(Locale.ROOT));
        if (app == null) {
            return "Applicazione sconosciuta: " + name + ". Catalogo: " + apps.keySet();
        }
        boolean wantsTarget = app.command().contains(TARGET);
        if (wantsTarget && (target == null || target.isBlank())) {
            return name + " va avviata con un file o una cartella (parametro target).";
        }
        String resolved = null;
        if (wantsTarget) {
            Path path = Path.of(target.strip()).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                return "Il percorso " + path + " non esiste.";
            }
            resolved = path.toString();
        }
        String finalTarget = resolved;
        List<String> command = app.command().stream()
                .map(part -> part.equals(TARGET) ? finalTarget : part)
                .toList();
        String shown = String.join(" ", command);
        return run(toolContext, resolved == null
                ? text("Avviare %s:\n%s", "Start %s:\n%s").in(language, app.description(), shown)
                : text("Avviare %s su %s:\n%s", "Start %s on %s:\n%s").in(language, app.description(), resolved, shown),
                command);
    }

    @Tool(description = "Elenca le applicazioni che Jarvis può avviare.")
    public String listApps() {
        return apps.isEmpty() ? "Nessuna applicazione nel catalogo (jarvis.desktop.apps)."
                : apps.entrySet().stream()
                .map(app -> "- " + app.getKey() + ": " + app.getValue().description()
                        + (app.getValue().command().contains(TARGET) ? " (con file o cartella)" : ""))
                .collect(Collectors.joining("\n"));
    }

    private String run(ToolContext toolContext, String description, List<String> command) {
        String sessionId = (String) toolContext.getContext().get(AgentService.SESSION_ID_KEY);
        String conversationId = (String) toolContext.getContext().get(AgentService.CONVERSATION_ID_KEY);
        if (!approvals.requestApproval(conversationId, sessionId, description)) {
            return "L'utente non ha confermato. Non ho aperto nulla.";
        }
        try {
            new ProcessBuilder(command).start(); // fire and forget: the app lives on its own
            return "Fatto.";
        } catch (IOException e) {
            return "Non riesco ad avviarlo: " + e.getMessage();
        }
    }

    /** The system's "open with the default program", for links and folders. */
    static List<String> opener(String target) {
        List<String> command = new ArrayList<>();
        command.add(WINDOWS ? "explorer.exe" : MAC ? "open" : "xdg-open");
        command.add(target);
        return command;
    }
}
