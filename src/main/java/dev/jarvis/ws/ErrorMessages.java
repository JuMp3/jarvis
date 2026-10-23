package dev.jarvis.ws;

import static dev.jarvis.i18n.Text.text;

import java.io.IOException;

import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import dev.jarvis.i18n.Text;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.voice.LocalVoiceException;
import dev.jarvis.voice.VoiceException;

/**
 * Turns failures into short sentences in the user's language, that can be shown and spoken. Technical details stay
 * in the logs.
 */
public final class ErrorMessages {

    static final int NO_STATUS = 0;
    private static final int MAX_CAUSE_DEPTH = 20;

    private ErrorMessages() {
    }

    public static String forProvider(String language, ProviderId provider, Throwable error) {
        if (isNetwork(error)) {
            return provider == ProviderId.OLLAMA
                    ? text("Non riesco a raggiungere Ollama. Verifica che sia avviato.",
                            "I can't reach Ollama. Check that it is running.").in(language)
                    : text("Non riesco a raggiungere %s. Controlla la connessione a internet.",
                            "I can't reach %s. Check your internet connection.").in(language, provider.label());
        }
        return describe(language, provider.label(), statusOf(error));
    }

    static String forVoice(String language, Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof LocalVoiceException local) {
                return local.getMessage(); // already written for the user
            }
        }
        if (isNetwork(error)) {
            return text("Non riesco a raggiungere il servizio vocale. Se è quello sul tuo PC, riavvia Jarvis "
                            + "(dettagli in ~/.jarvis/logs).",
                    "I can't reach the voice service. If it is the one on your PC, restart Jarvis "
                            + "(details in ~/.jarvis/logs).").in(language);
        }
        return describe(language, text("Il servizio vocale", "The voice service").in(language), statusOf(error));
    }

    static String describe(String language, String service, int status) {
        Text message = switch (status) {
            case 401, 403 -> text("%s non accetta la chiave API. Controlla la configurazione.",
                    "%s does not accept the API key. Check the configuration.");
            case 429 -> text("%s ha raggiunto il limite di richieste, o il credito è esaurito. Riprova tra poco.",
                    "%s has reached its request limit, or the credit has run out. Try again in a moment.");
            case 400, 404, 422 -> text("%s ha rifiutato la richiesta. Controlla il modello e la configurazione.",
                    "%s refused the request. Check the model and the configuration.");
            default -> status >= 500
                    ? text("%s è sovraccarico o non disponibile. Riprova tra poco.",
                            "%s is overloaded or unavailable. Try again in a moment.")
                    : text("Qualcosa è andato storto. Riprova.", "Something went wrong. Try again.");
        };
        return message.in(language, service);
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
