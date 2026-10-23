package dev.jarvis.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class AzureSpeechTest {

    @Test
    void sendsTheTextAsEscapedSsmlWithTheKeyAndReturnsMp3() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cognitiveservices/v1", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst(AzureSpeech.KEY_HEADER));
            byte[] mp3 = {(byte) 0xFF, (byte) 0xF3, 1};
            exchange.sendResponseHeaders(200, mp3.length);
            exchange.getResponseBody().write(mp3);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cognitiveservices/v1");
            TextToSpeech.SpeechAudio audio = new AzureSpeech(HttpClient.newHttpClient(), endpoint, "azure-key",
                    "it-IT-DiegoNeural", 0).synthesize("Tom & Jerry <voice name='x'>");

            assertThat(audio.mimeType()).isEqualTo("audio/mpeg");
            assertThat(audio.data()).hasSize(3);
            assertThat(key.get()).isEqualTo("azure-key");
            assertThat(body.get()).isEqualTo("<speak version='1.0' xml:lang='it-IT'><voice name='it-IT-DiegoNeural'>"
                    + "Tom &amp; Jerry &lt;voice name=&apos;x&apos;&gt;</voice></speak>");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theRegionAndTheVoiceCannotChangeTheAddress() {
        assertThat(AzureSpeech.synthesisUri("Italy North").getHost()).isEqualTo("italynorth.tts.speech.microsoft.com");
        assertThatThrownBy(() -> AzureSpeech.synthesisUri("evil.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AzureSpeech(HttpClient.newHttpClient(), "westeurope", "k", "x'/><voice", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFasterPaceWrapsTheTextInProsody() {
        assertThat(AzureSpeech.ssml("it-IT-DiegoNeural", 20, "Ciao")).isEqualTo("<speak version='1.0' xml:lang='it-IT'>"
                + "<voice name='it-IT-DiegoNeural'><prosody rate='+20%'>Ciao</prosody></voice></speak>");
        assertThat(AzureSpeech.rate("10")).isEqualTo(10);
        assertThat(AzureSpeech.rate("molto")).isZero();
    }
}
