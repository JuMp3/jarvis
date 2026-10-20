package dev.jarvis.agent.tools;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Keeps file access inside a sandbox folder. Every tool that touches the filesystem goes through here.
 */
public final class SafePaths {

    private static final int MAX_NAME_LENGTH = 80;

    private SafePaths() {
    }

    /**
     * Turns a free-form title into a safe file name: ASCII letters, digits and dashes only.
     * "Idee per il viaggio!" becomes "idee-per-il-viaggio.md".
     */
    public static String toFileName(String title, String extension) {
        String ascii = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_NAME_LENGTH) {
            slug = slug.substring(0, MAX_NAME_LENGTH).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            slug = "note";
        }
        return slug + extension;
    }

    /**
     * Resolves {@code fileName} inside {@code root}.
     *
     * @throws IllegalArgumentException if the result would escape the root folder
     */
    public static Path resolveInside(Path root, String fileName) {
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(fileName).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("Path escapes the sandbox: " + fileName);
        }
        return resolved;
    }
}
