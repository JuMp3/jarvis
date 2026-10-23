package dev.jarvis.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import dev.jarvis.llm.ProviderId;
import dev.jarvis.voice.LocalVoiceException;
import dev.jarvis.voice.VoiceException;

class ErrorMessagesTest {

    @Test
    void mapsStatusCodesToActionableSentences() {
        assertThat(ErrorMessages.describe("it", "Claude", 401)).contains("chiave API");
        assertThat(ErrorMessages.describe("it", "Claude", 429)).contains("credito");
        assertThat(ErrorMessages.describe("it", "Claude", 529)).contains("sovraccarico");
        assertThat(ErrorMessages.describe("it", "Claude", 400)).contains("rifiutato");
        assertThat(ErrorMessages.describe("it", "Claude", ErrorMessages.NO_STATUS)).isEqualTo("Qualcosa è andato storto. Riprova.");
    }

    @Test
    void findsTheStatusDeepInTheCauseChain() {
        WebClientResponseException response = WebClientResponseException.create(
                503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        RuntimeException wrapped = new RuntimeException("stream failed", new IllegalStateException(response));

        assertThat(ErrorMessages.statusOf(wrapped)).isEqualTo(503);
        assertThat(ErrorMessages.forProvider("it", ProviderId.OPENAI, wrapped)).startsWith("OpenAI è sovraccarico");
    }

    @Test
    void reportsUnreachableOllamaAsNotRunning() {
        WebClientRequestException refused = new WebClientRequestException(new ConnectException("Connection refused"),
                HttpMethod.POST, URI.create("http://localhost:11434/api/chat"),
                HttpHeaders.EMPTY);

        assertThat(ErrorMessages.forProvider("it", ProviderId.OLLAMA, refused)).contains("Verifica che sia avviato");
    }

    @Test
    void describesVoiceFailures() {
        assertThat(ErrorMessages.forVoice("it", new VoiceException("Audio API returned 429: quota", 429)))
                .isEqualTo("Il servizio vocale ha raggiunto il limite di richieste, o il credito è esaurito. Riprova tra poco.");
        assertThat(ErrorMessages.forVoice("it", new VoiceException("unreachable", new ConnectException())))
                .contains("riavvia Jarvis");
        assertThat(ErrorMessages.forVoice("it", new RuntimeException(new LocalVoiceException("La voce sul tuo PC non è pronta.", null))))
                .isEqualTo("La voce sul tuo PC non è pronta.");
    }

    @Test
    void speaksEnglishWhenTheUserDoes() {
        assertThat(ErrorMessages.describe("en", "Claude", 401)).isEqualTo("Claude does not accept the API key. Check the configuration.");
        assertThat(ErrorMessages.forVoice("en", new VoiceException("unreachable", new ConnectException())))
                .startsWith("I can't reach the voice service");
    }
}
