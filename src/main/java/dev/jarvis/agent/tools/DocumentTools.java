package dev.jarvis.agent.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;

/**
 * Read-only access to the user's documents under {@code jarvis.documents.folders}: list, search, read.
 * Given only to the documents specialist: document content is untrusted, so whoever reads it cannot act.
 */
@Component
public class DocumentTools {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_LISTED = 200;
    private static final int MAX_SCANNED = 300;
    private static final int MAX_FOUND = 20;
    private static final int MAX_READ_CHARS = 40_000;
    private static final int SNIPPET = 120;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN)
            .withZone(ZoneId.systemDefault());

    private final AllowedFolders folders;

    public DocumentTools(JarvisProperties properties) {
        List<String> configured = properties.documents() == null || properties.documents().folders() == null
                ? List.of() : properties.documents().folders();
        configured.forEach(DocumentTools::createIfMissing);
        this.folders = new AllowedFolders(configured);
    }

    @Tool(description = "Elenca i documenti leggibili (testo, PDF, Word) in una cartella consentita, o in tutte.")
    public String listDocuments(
            @ToolParam(description = "Cartella da elencare; vuoto per tutte le cartelle consentite", required = false) String folder) {
        List<Path> roots = folder == null || folder.isBlank() ? folders.roots() : List.of(folders.resolve(folder));
        List<String> lines = documents(roots).limit(MAX_LISTED + 1L).map(DocumentTools::describe).toList();
        if (lines.isEmpty()) {
            return "Nessun documento. Cartelle consentite: " + folders.roots();
        }
        return String.join("\n", lines.subList(0, Math.min(lines.size(), MAX_LISTED)))
                + (lines.size() > MAX_LISTED ? "\n… elenco troncato a " + MAX_LISTED + " file" : "");
    }

    @Tool(description = "Cerca nei documenti consentiti un testo nel nome del file o nel contenuto.")
    public String searchDocuments(@ToolParam(description = "Parole da cercare") String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return "Indica cosa cercare.";
        }
        List<String> found = new ArrayList<>();
        for (Path file : documents(folders.roots()).limit(MAX_SCANNED).toList()) {
            if (found.size() >= MAX_FOUND) {
                break;
            }
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                found.add(file + " (nel nome)");
                continue;
            }
            snippet(file, needle).ifPresent(snippet -> found.add(file + "\n  …" + snippet + "…"));
        }
        return found.isEmpty() ? "Nessun documento contiene \"" + query + "\"." : String.join("\n", found);
    }

    @Tool(description = "Legge il testo di un documento consentito (testo, PDF, Word).")
    public String readDocument(@ToolParam(description = "Percorso del documento") String path) {
        Path file = folders.resolve(path);
        if (!Files.isRegularFile(file) || !DocumentText.supported(file)) {
            return file + " non è un documento leggibile (testo, PDF o Word).";
        }
        try {
            String text = DocumentText.extract(file);
            return text.length() <= MAX_READ_CHARS ? text
                    : text.substring(0, MAX_READ_CHARS) + "\n… documento troncato a " + MAX_READ_CHARS + " caratteri";
        } catch (IOException e) {
            return "Non riesco a leggere " + file + ": " + e.getMessage();
        }
    }

    private static Stream<Path> documents(List<Path> roots) {
        return roots.stream().flatMap(root -> {
            try (Stream<Path> files = Files.walk(root, MAX_DEPTH)) {
                return files.filter(Files::isRegularFile)
                        .filter(DocumentText::supported)
                        .filter(file -> !isHidden(root, file))
                        .sorted()
                        .toList().stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Skips .git, .obsidian and similar folders, and hidden files. */
    private static boolean isHidden(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> snippet(Path file, String needle) {
        try {
            String text = DocumentText.extract(file);
            int at = text.toLowerCase(Locale.ROOT).indexOf(needle);
            if (at < 0) {
                return Optional.empty();
            }
            int from = Math.max(0, at - SNIPPET / 2);
            int to = Math.min(text.length(), at + needle.length() + SNIPPET / 2);
            return Optional.of(text.substring(from, to).replaceAll("\\s+", " ").strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String describe(Path file) {
        try {
            long kb = Math.max(1, Files.size(file) / 1024);
            return file + " (" + kb + " KB, " + DAY.format(Files.getLastModifiedTime(file).toInstant()) + ")";
        } catch (IOException e) {
            return file.toString();
        }
    }

    private static void createIfMissing(String folder) {
        try {
            Files.createDirectories(Path.of(folder));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create jarvis.documents.folders entry " + folder, e);
        }
    }
}
