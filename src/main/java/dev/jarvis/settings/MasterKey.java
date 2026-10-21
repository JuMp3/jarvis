package dev.jarvis.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/**
 * The symmetric key that encrypts secret settings. It never goes to the database: a dump or a backup of the
 * database alone does not reveal the secrets. It protects against that, not against someone who can log in
 * as the user.
 */
public final class MasterKey {

    public static final String ENVIRONMENT_VARIABLE = "JARVIS_MASTER_KEY";

    private MasterKey() {
    }

    /**
     * @param fromEnvironment Base64 key from {@value #ENVIRONMENT_VARIABLE}, or null
     * @param file            key file, created with a new random key on first use (readable by the user only)
     */
    public static byte[] load(String fromEnvironment, Path file) {
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Base64.getDecoder().decode(fromEnvironment.strip());
        }
        try {
            if (Files.notExists(file)) {
                create(file);
            }
            return Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the master key " + file, e);
        }
    }

    private static void create(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file); // Windows: the user profile is already private to the user
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(SecretBox.newKey()), StandardCharsets.US_ASCII);
        } catch (FileAlreadyExistsException raced) {
            // created meanwhile by another start: use that one
        }
    }
}
