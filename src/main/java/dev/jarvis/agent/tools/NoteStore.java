package dev.jarvis.agent.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Markdown notes stored in a sandbox folder. Pure Java, no Spring dependency.
 */
public class NoteStore {

    private static final String EXTENSION = ".md";

    private final Path root;

    public NoteStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Where a note with this title would be written. */
    public Path pathFor(String title) {
        return SafePaths.resolveInside(root, SafePaths.toFileName(title, EXTENSION));
    }

    public Path save(String title, String content) {
        Path target = pathFor(title);
        try {
            Files.createDirectories(root);
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save note " + target.getFileName(), e);
        }
    }

    public List<String> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list notes", e);
        }
    }
}
