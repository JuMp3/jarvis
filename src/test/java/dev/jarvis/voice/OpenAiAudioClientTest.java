package dev.jarvis.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class OpenAiAudioClientTest {

    private static final OpenAiAudioClient.Settings SETTINGS = new OpenAiAudioClient.Settings(
            "test-key", "it", "stt-model", "tts-model", "onyx", "Voce \"calma\"");

    private record Captured(String path, String auth, String contentType, String body) {
    }

    private static HttpServer server(int status, byte[] response, AtomicReference<Captured> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.set(new Captured(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(body, StandardCharsets.ISO_8859_1)));
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static OpenAiAudioClient client(HttpServer server) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        return new OpenAiAudioClient(HttpClient.newHttpClient(), base, SETTINGS);
    }

    @Test
    void transcribeSendsMultipartAndReturnsText() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = server(200, "  che tempo fa domani?\n".getBytes(StandardCharsets.UTF_8), captured);
        try {
            String text = client(server).transcribe(new byte[] {1, 2, 3}, "audio/webm;codecs=opus");

            assertEquals("che tempo fa domani?", text);
            Captured request = captured.get();
            assertEquals("/v1/audio/transcriptions", request.path());
            assertEquals("Bearer test-key", request.auth());
            assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
            assertTrue(request.body().contains("name=\"file\"; filename=\"speech.webm\""));
            assertTrue(request.body().contains("name=\"model\"\r\n\r\nstt-model"));
            assertTrue(request.body().contains("name=\"language\"\r\n\r\nit"));
            assertTrue(request.body().contains("name=\"response_format\"\r\n\r\ntext"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void synthesizeSendsJsonAndReturnsAudio() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, 0x10};
        HttpServer server = server(200, mp3, captured);
        try {
            TextToSpeech.SpeechAudio audio = client(server).synthesize("Ciao!\nCome stai?");

            assertArrayEquals(mp3, audio.data());
            assertEquals("audio/mpeg", audio.mimeType());
            Captured request = captured.get();
            assertEquals("/v1/audio/speech", request.path());
            assertEquals("application/json", request.contentType());
            assertTrue(request.body().contains("\"input\":\"Ciao!\\nCome stai?\""));
            assertTrue(request.body().contains("\"instructions\":\"Voce \\\"calma\\\"\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorsAreReported() throws Exception {
        HttpServer server = server(401, "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8), new AtomicReference<>());
        try {
            VoiceException error = assertThrows(VoiceException.class, () -> client(server).synthesize("Ciao"));
            assertTrue(error.getMessage().contains("401"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void detectsRecordedFormats() {
        assertEquals("audio/webm", AudioFormats.detectMime(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0}));
        assertEquals("audio/mp4", AudioFormats.detectMime(new byte[] {0, 0, 0, 0x20, 'f', 't', 'y', 'p'}));
        assertEquals("audio/ogg", AudioFormats.detectMime("OggS....".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(AudioFormats.DEFAULT_MIME, AudioFormats.detectMime(new byte[] {1}));
        assertEquals(".m4a", OpenAiAudioClient.extensionFor("audio/mp4"));
    }
}
