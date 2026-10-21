package dev.jarvis.ws;

import java.io.IOException;

import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import dev.jarvis.llm.ProviderId;
import dev.jarvis.voice.VoiceException;

/**
 * Turns failures into short Italian sentences that can be shown and spoken. Technical details stay in
 * the logs.
 */
public final class ErrorMessages {

    static final int NO_STATUS = 0;
    private static final int MAX_CAUSE_DEPTH = 20;

    private ErrorMessages() {
    }

    public static String forProvider(ProviderId provider, Throwable error) {
        if (isNetwork(error)) {
            return provider == ProviderId.OLLAMA
                    ? "Non riesco a raggiungere Ollama. Verifica che sia avviato."
                    : "Non riesco a raggiungere " + provider.label() + ". Controlla la connessione a internet.";
        }
        return describe(provider.label(), statusOf(error));
    }

    static String forVoice(Throwable error) {
        if (isNetwork(error)) {
            return "Non riesco a raggiungere il servizio vocale. Se è locale, verifica che Speaches sia avviato.";
        }
        return describe("Il servizio vocale", statusOf(error));
    }

    static String describe(String service, int status) {
        return switch (status) {
            case 401, 403 -> service + " non accetta la chiave API. Controlla la configurazione.";
            case 429 -> service + " ha raggiunto il limite di richieste, o il credito è esaurito. Riprova tra poco.";
            case 400, 404, 422 -> service + " ha rifiutato la richiesta. Controlla il modello e la configurazione.";
            default -> status >= 500
                    ? service + " è sovraccarico o non disponibile. Riprova tra poco."
                    : "Qualcosa è andato storto. Riprova.";
        };
    }

    /** First HTTP status found in the cause chain, {@link #NO_STATUS} if none. */
    static int statusOf(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            int status = switch (current) {
                case AnthropicServiceException e -> e.statusCode();
                case OpenAIServiceException e -> e.statusCode();
                case WebClientResponseException e -> e.getStatusCode().value();
                case RestClientResponseException e -> e.getStatusCode().value();
                case VoiceException e -> e.statusCode();
                default -> NO_STATUS;
            };
            if (status != NO_STATUS) {
                return status;
            }
        }
        return NO_STATUS;
    }

    private static boolean isNetwork(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof IOException || current instanceof AnthropicIoException
                    || current instanceof OpenAIIoException) {
                return true;
            }
        }
        return false;
    }
}
