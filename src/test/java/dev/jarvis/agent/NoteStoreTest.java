package dev.jarvis.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jarvis.agent.tools.NoteStore;
import dev.jarvis.agent.tools.SafePaths;

class NoteStoreTest {

    @Test
    void titlesBecomeSafeFileNames() {
        assertEquals("idee-per-il-weekend.md", SafePaths.toFileName("Idee per il weekend!", ".md"));
        assertEquals("perche-cosi.md", SafePaths.toFileName("Perché così?", ".md"));
        assertEquals("etc-passwd.md", SafePaths.toFileName("../../etc/passwd", ".md"));
        assertEquals("note.md", SafePaths.toFileName("???", ".md"));
    }

    @Test
    void refusesPathsOutsideTheSandbox() throws Exception {
        Path root = Files.createTempDirectory("jarvis-notes");
        assertThrows(IllegalArgumentException.class, () -> SafePaths.resolveInside(root, "../escape.md"));
        assertThrows(IllegalArgumentException.class, () -> SafePaths.resolveInside(root, "/etc/passwd"));
        assertTrue(SafePaths.resolveInside(root, "ok.md").startsWith(root.toAbsolutePath().normalize()));
    }

    @Test
    void savesAndListsNotes() throws Exception {
        Path root = Files.createTempDirectory("jarvis-notes").resolve("nested");
        NoteStore store = new NoteStore(root);
        assertEquals(List.of(), store.list());

        Path saved = store.save("Lista spesa", "- latte\n- pane");

        assertEquals("- latte\n- pane", Files.readString(saved));
        assertEquals(List.of("lista-spesa.md"), store.list());
    }
}
