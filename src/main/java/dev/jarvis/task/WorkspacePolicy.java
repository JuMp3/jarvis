package dev.jarvis.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Which repositories the coder may work on: git repositories under the configured roots. Paths are resolved
 * to their real location first, so ".." and links cannot escape a root. Pure logic, no Spring dependency.
 */
final class WorkspacePolicy {

    private static final int SEARCH_DEPTH = 2;

    private final List<Path> roots;

    /** Roots that do not exist are ignored. */
    WorkspacePolicy(List<String> roots) {
        this.roots = roots == null ? List.of()
                : roots.stream().map(Path::of).map(WorkspacePolicy::real).flatMap(Optional::stream).toList();
    }

    /**
     * @return the real path of the repository
     * @throws IllegalArgumentException with a message for the orchestrator if the path is not allowed
     */
    Path resolve(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("Indica il repository su cui lavorare (parametro workspace). "
                    + "Cartelle consentite: " + roots + ".");
        }
        Path repository = real(Path.of(workspace.strip()))
                .orElseThrow(() -> new IllegalArgumentException("Il percorso " + workspace + " non esiste."));
        if (roots.stream().noneMatch(repository::startsWith)) {
            throw new IllegalArgumentException(repository + " è fuori dalle cartelle consentite: " + roots + ".");
        }
        if (!Files.exists(repository.resolve(".git"))) {
            throw new IllegalArgumentException(repository + " non è un repository git.");
        }
        return repository;
    }

    /** Git repositories under the roots, up to two levels deep. */
    List<Path> repositories() {
        return roots.stream().flatMap(WorkspacePolicy::gitRepositories).sorted().toList();
    }

    List<Path> roots() {
        return roots;
    }

    private static Stream<Path> gitRepositories(Path root) {
        try (Stream<Path> dirs = Files.walk(root, SEARCH_DEPTH)) {
            return dirs.filter(Files::isDirectory).filter(dir -> Files.exists(dir.resolve(".git"))).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Path> real(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
