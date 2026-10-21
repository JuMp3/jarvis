package dev.jarvis.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Speech-to-text and text-to-speech through the OpenAI audio API, or any server that implements it
 * (e.g. Speaches running locally), using only the JDK HTTP client.
 */
public class OpenAiAudioClient implements SpeechToText, TextToSpeech {

    /** Settings for the audio endpoints. {@code apiKey} and {@code ttsInstructions} may be blank. */
    public record Settings(String apiKey, String language, String sttModel, String ttsModel,
                           String ttsVoice, String ttsInstructions) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int ERROR_BODY_LIMIT = 300;

    private final HttpClient http;
    private final URI baseUri;
    private final Settings settings;

    public OpenAiAudioClient(HttpClient http, URI baseUri, Settings settings) {
        this.http = http;
        this.baseUri = baseUri;
        this.settings = settings;
    }

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        Multipart form = new Multipart()
                .file("file", "speech" + extensionFor(mimeType), mimeType, audio)
                .field("model", settings.sttModel())
                .field("response_format", "text");
        if (settings.language() != null && !settings.language().isBlank()) {
            form.field("language", settings.language());
        }
        HttpRequest request = baseRequest("audio/transcriptions")
                .header("Content-Type", form.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(form.build()))
                .build();
        HttpResponse<byte[]> response = send(request);
        return new String(response.body(), StandardCharsets.UTF_8).trim();
    }

    @Override
    public SpeechAudio synthesize(String text) {
        StringBuilder json = new StringBuilder("{")
                .append("\"model\":").append(Json.quote(settings.ttsModel()))
                .append(",\"voice\":").append(Json.quote(settings.ttsVoice()))
                .append(",\"input\":").append(Json.quote(text))
                .append(",\"response_format\":\"mp3\"");
        if (settings.ttsInstructions() != null && !settings.ttsInstructions().isBlank()) {
            json.append(",\"instructions\":").append(Json.quote(settings.ttsInstructions()));
        }
        json.append('}');

        HttpRequest request = baseRequest("audio/speech")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                .build();
        return new SpeechAudio(send(request).body(), "audio/mpeg");
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(TIMEOUT);
        if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + settings.apiKey());
        }
        return request;
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                throw new VoiceException("Audio API " + baseUri + " returned " + response.statusCode() + ": "
                        + body.substring(0, Math.min(body.length(), ERROR_BODY_LIMIT)), response.statusCode());
            }
            return response;
        } catch (IOException e) {
            throw new VoiceException("Audio API " + baseUri + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("Interrupted while calling the audio API " + baseUri, e);
        }
    }

    static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return ".webm";
        }
        String base = mimeType.split(";")[0].trim();
        return switch (base) {
            case "audio/ogg" -> ".ogg";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a";
            case "audio/mpeg" -> ".mp3";
            case "audio/wav", "audio/x-wav" -> ".wav";
            default -> ".webm";
        };
    }
}
