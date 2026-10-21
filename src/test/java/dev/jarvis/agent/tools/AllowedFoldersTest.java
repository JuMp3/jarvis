package dev.jarvis.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowedFoldersTest {

    @TempDir
    Path tmp;

    @Test
    void resolvesInsideTheRootsOnly() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("docs"));
        Path file = Files.writeString(root.resolve("a.txt"), "x");
        Files.writeString(tmp.resolve("secret.txt"), "x");
        AllowedFolders folders = new AllowedFolders(List.of(root.toString()));

        assertThat(folders.resolve("a.txt")).isEqualTo(file.toRealPath());
        assertThat(folders.resolve(file.toString())).isEqualTo(file.toRealPath());
        assertThatThrownBy(() -> folders.resolve("../secret.txt")).hasMessageContaining("fuori dalle cartelle");
        assertThatThrownBy(() -> folders.resolve(tmp.resolve("secret.txt").toString())).hasMessageContaining("fuori");
        assertThatThrownBy(() -> new AllowedFolders(List.of()).resolve("a.txt")).hasMessageContaining("Nessuna cartella");
    }
}
