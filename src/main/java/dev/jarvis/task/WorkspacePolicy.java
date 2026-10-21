package dev.jarvis.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import dev.jarvis.agent.tools.AllowedFolders;

/**
 * Which repositories the coder may work on: git repositories under the configured roots. Pure logic.
 */
final class WorkspacePolicy {

    private static final int SEARCH_DEPTH = 2;

    private final AllowedFolders folders;

    /** Roots that do not exist are ignored. */
    WorkspacePolicy(List<String> roots) {
        this.folders = new AllowedFolders(roots);
    }

    /**
     * @return the real path of the repository
     * @throws IllegalArgumentException with a message for the orchestrator if the path is not allowed
     */
    Path resolve(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("Indica il repository su cui lavorare (parametro workspace). "
                    + "Cartelle consentite: " + folders.roots() + ".");
        }
        Path repository = folders.resolve(workspace);
        if (!Files.exists(repository.resolve(".git"))) {
            throw new IllegalArgumentException(repository + " non è un repository git.");
        }
        return repository;
    }

    /** Git repositories under the roots, up to two levels deep. */
    List<Path> repositories() {
        return folders.roots().stream().flatMap(WorkspacePolicy::gitRepositories).sorted().toList();
    }

    List<Path> roots() {
        return folders.roots();
    }

    private static Stream<Path> gitRepositories(Path root) {
        try (Stream<Path> dirs = Files.walk(root, SEARCH_DEPTH)) {
            return dirs.filter(Files::isDirectory).filter(dir -> Files.exists(dir.resolve(".git"))).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
