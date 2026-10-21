package dev.jarvis.agent.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Confines access to a set of root folders. Paths are resolved to their real location first, so ".." and
 * links cannot escape a root. Relative paths are resolved against the first root. Pure logic.
 */
public final class AllowedFolders {

    private final List<Path> roots;

    /** Roots that do not exist are ignored. */
    public AllowedFolders(List<String> roots) {
        this.roots = roots == null ? List.of()
                : roots.stream().map(Path::of).map(AllowedFolders::real).flatMap(Optional::stream).toList();
    }

    public List<Path> roots() {
        return roots;
    }

    /**
     * @return the real path, inside one of the roots
     * @throws IllegalArgumentException with a message for the model if the path is missing or outside
     */
    public Path resolve(String path) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("Nessuna cartella consentita è configurata.");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Indica un percorso. Cartelle consentite: " + roots + ".");
        }
        Path requested = Path.of(path.strip());
        Path real = real(requested.isAbsolute() ? requested : roots.getFirst().resolve(requested))
                .orElseThrow(() -> new IllegalArgumentException("Il percorso " + path + " non esiste."));
        if (roots.stream().noneMatch(real::startsWith)) {
            throw new IllegalArgumentException(real + " è fuori dalle cartelle consentite: " + roots + ".");
        }
        return real;
    }

    private static Optional<Path> real(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
