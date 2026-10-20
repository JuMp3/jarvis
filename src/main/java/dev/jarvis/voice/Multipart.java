package dev.jarvis.voice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal multipart/form-data body builder for {@link java.net.http.HttpClient}.
 */
final class Multipart {

    private static final String CRLF = "\r\n";

    private final String boundary = "jarvis-" + UUID.randomUUID();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    Multipart field(String name, String value) {
        write("--" + boundary + CRLF);
        write("Content-Disposition: form-data; name=\"" + name + "\"" + CRLF + CRLF);
        write(value + CRLF);
        return this;
    }

    Multipart file(String name, String fileName, String contentType, byte[] data) {
        write("--" + boundary + CRLF);
        write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"" + CRLF);
        write("Content-Type: " + contentType + CRLF + CRLF);
        body.writeBytes(data);
        write(CRLF);
        return this;
    }

    byte[] build() {
        write("--" + boundary + "--" + CRLF);
        return body.toByteArray();
    }

    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private void write(String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
