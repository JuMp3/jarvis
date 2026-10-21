package dev.jarvis.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePolicyTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path repo;
    private WorkspacePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("allowed"));
        repo = Files.createDirectories(root.resolve("project/.git")).getParent();
        Files.createDirectories(tmp.resolve("outside/.git"));
        Files.createDirectories(root.resolve("not-a-repo"));
        policy = new WorkspacePolicy(List.of(root.toString(), tmp.resolve("missing").toString()));
    }

    @Test
    void acceptsAGitRepositoryUnderARoot() throws Exception {
        assertThat(policy.resolve(repo.toString())).isEqualTo(repo.toRealPath());
    }

    @Test
    void rejectsPathsOutsideTheRootsEvenThroughDotDot() {
        assertThatThrownBy(() -> policy.resolve(tmp.resolve("outside").toString()))
                .hasMessageContaining("fuori dalle cartelle consentite");
        assertThatThrownBy(() -> policy.resolve(root.resolve("../outside").toString()))
                .hasMessageContaining("fuori dalle cartelle consentite");
    }

    @Test
    void rejectsMissingPathsNonRepositoriesAndBlank() {
        assertThatThrownBy(() -> policy.resolve(root.resolve("nope").toString())).hasMessageContaining("non esiste");
        assertThatThrownBy(() -> policy.resolve(root.resolve("not-a-repo").toString())).hasMessageContaining("non è un repository git");
        assertThatThrownBy(() -> policy.resolve(" ")).hasMessageContaining("Indica il repository");
    }

    @Test
    void listsRepositoriesUnderTheRootsOnly() throws Exception {
        assertThat(policy.repositories()).containsExactly(repo.toRealPath());
        assertThat(policy.roots()).containsExactly(root.toRealPath());
    }
}
