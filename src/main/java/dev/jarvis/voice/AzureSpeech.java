package dev.jarvis.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Text-to-speech with Azure's neural voices, through their REST API (the JDK HTTP client only, as OpenAiAudioClient).
 * The free plan (F0) of a Speech resource covers half a million characters a month.
 */
public class AzureSpeech implements TextToSpeech {

    public static final String KEY_HEADER = "Ocp-Apim-Subscription-Key";
    /** Decoded by every browser, and small: about 6 KB per second of speech. */
    private static final String FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int ERROR_BODY_LIMIT = 300;
    private static final Pattern REGION = Pattern.compile("[a-z0-9]{2,40}");
    private static final Pattern VOICE = Pattern.compile("[a-z]{2}-[A-Z]{2}-[A-Za-z0-9:-]+");

    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String voice;
    private final int ratePercent;

    /**
     * @param voice       e.g. it-IT-DiegoNeural: its first part is the language of the text
     * @param ratePercent faster (positive) or slower (negative) than the voice's own pace, e.g. 10 for +10%
     */
    public AzureSpeech(HttpClient http, String region, String key, String voice, int ratePercent) {
        this(http, synthesisUri(region), key, voice, ratePercent);
    }

    AzureSpeech(HttpClient http, URI endpoint, String key, String voice, int ratePercent) {
        if (voice == null || !VOICE.matcher(voice).matches()) {
            throw new IllegalArgumentException("Not an Azure voice: " + voice);
        }
        this.http = http;
        this.endpoint = endpoint;
        this.key = key;
        this.voice = voice;
        this.ratePercent = Math.clamp(ratePercent, -50, 100);
    }

    /** The pace of the Settings ("0", "10"...): anything else is the voice's own. */
    public static int rate(String setting) {
        try {
            return setting == null || setting.isBlank() ? 0 : Integer.parseInt(setting.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The region goes into the host name: only letters and digits, as Azure writes it (italynorth, westeurope). */
    public static URI synthesisUri(String region) {
        return URI.create("https://" + checked(region) + ".tts.speech.microsoft.com/cognitiveservices/v1");
    }

    public static URI voicesUri(String region) {
        return URI.create("https://" + checked(region) + ".tts.speech.microsoft.com/cognitiveservices/voices/list");
    }

    private static String checked(String region) {
        String value = region == null ? "" : region.strip().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (!REGION.matcher(value).matches()) {
            throw new IllegalArgumentException("Not an Azure region: " + region);
        }
        return value;
    }

    @Override
    public SpeechAudio synthesize(String text) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header(KEY_HEADER, key)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", FORMAT)
                .header("User-Agent", "Jarvis")
                .POST(HttpRequest.BodyPublishers.ofString(ssml(voice, ratePercent, text), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                throw new VoiceException("Azure speech returned " + response.statusCode() + ": "
                        + body.substring(0, Math.min(ERROR_BODY_LIMIT, body.length())), response.statusCode());
            }
            return new SpeechAudio(response.body(), "audio/mpeg");
        } catch (IOException e) {
            throw new VoiceException("Azure speech unreachable at " + endpoint.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("Interrupted while calling Azure speech", e);
        }
    }

    /** The text is data: escaped, so nothing in an answer can change the voice or add markup. */
    static String ssml(String voice, int ratePercent, String text) {
        String locale = voice.substring(0, 5);
        String spoken = escape(text.replace('\n', ' '));
        if (ratePercent != 0) {
            spoken = "<prosody rate='" + (ratePercent > 0 ? "+" : "") + ratePercent + "%'>" + spoken + "</prosody>";
        }
        return "<speak version='1.0' xml:lang='" + locale + "'><voice name='" + voice + "'>" + spoken + "</voice></speak>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
