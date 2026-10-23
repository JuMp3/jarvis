package dev.jarvis.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.Voice;
import dev.jarvis.config.JarvisProperties.Voice.AudioEndpoint;
import dev.jarvis.config.JarvisProperties.Voice.Engine;

@Configuration(proxyBeanMethods = false)
public class VoiceConfig {

    private static final Logger log = LoggerFactory.getLogger(VoiceConfig.class);

    private LocalVoice local;

    @Bean
    VoiceServices voiceServices(JarvisProperties properties) {
        Voice voice = properties.voice();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (voice.stt() == Engine.LOCAL || voice.tts() == Engine.LOCAL) {
            local = new LocalVoice(voice.local(), properties.language());
        }
        Optional<SpeechToText> stt = switch (voice.stt()) {
            case OPENAI -> client(voice, voice.openai(), properties.language(), "jarvis.voice.stt=openai", http)
                    .map(SpeechToText.class::cast);
            case GROQ -> client(voice, voice.groq(), properties.language(), "jarvis.voice.stt=groq", http)
                    .map(SpeechToText.class::cast);
            case LOCAL -> Optional.of(local.speechToText(http, settings(voice, voice.openai(), properties.language())));
            case AZURE -> { // only its voices are wired here
                log.warn("jarvis.voice.stt=azure is not supported: transcription disabled");
                yield Optional.empty();
            }
            case BROWSER, OFF -> Optional.empty();
        };
        Optional<TextToSpeech> tts = switch (voice.tts()) {
            case OPENAI -> client(voice, voice.openai(), properties.language(), "jarvis.voice.tts=openai", http)
                    .map(TextToSpeech.class::cast);
            case LOCAL -> Optional.of(local.textToSpeech());
            case AZURE -> azure(voice.azure(), properties.language(), http).map(TextToSpeech.class::cast);
            case GROQ -> { // its voices do not speak Italian
                log.warn("jarvis.voice.tts=groq is not supported: voice disabled in this direction");
                yield Optional.empty();
            }
            case BROWSER, OFF -> Optional.empty();
        };
        return new VoiceServices(stt, tts, mode(voice.stt(), stt), mode(voice.tts(), tts), properties.language(),
                wakeWord(voice));
    }

    /** The local engines end with the application, also at the restart that applies new settings. */
    @PreDestroy
    void close() {
        if (local != null) {
            local.close();
        }
    }

    /** A service with the OpenAI audio API (OpenAI, Groq). Empty without a key: the voice is off in that direction. */
    private static Optional<OpenAiAudioClient> client(Voice voice, AudioEndpoint endpoint, String language,
                                                      String chosen, HttpClient http) {
        if (endpoint == null || endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            log.warn("{} but no API key for it is configured: voice disabled in this direction", chosen);
            return Optional.empty();
        }
        return Optional.of(new OpenAiAudioClient(http, URI.create(endpoint.baseUrl()), settings(voice, endpoint, language)));
    }

    /** Empty without key or region: the voice is off. */
    private static Optional<AzureSpeech> azure(Voice.Azure azure, String language, HttpClient http) {
        if (azure == null || azure.key() == null || azure.key().isBlank() || azure.region() == null
                || azure.region().isBlank()) {
            log.warn("jarvis.voice.tts=azure but its key or region is missing: voice disabled");
            return Optional.empty();
        }
        String voice = azure.voices() == null ? null : azure.voices().get(language == null ? "it" : language);
        try {
            return Optional.of(new AzureSpeech(http, azure.region(), azure.key(), voice, AzureSpeech.rate(azure.rate())));
        } catch (IllegalArgumentException e) {
            log.warn("Azure voice disabled: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** For the local whisper.cpp server only the language and the prompt matter: it has one model loaded. */
    private static OpenAiAudioClient.Settings settings(Voice voice, AudioEndpoint endpoint, String language) {
        return new OpenAiAudioClient.Settings(endpoint.apiKey(), language, endpoint.sttModel(),
                endpoint.ttsModel(), endpoint.ttsVoice(), endpoint.ttsInstructions(), wakeWord(voice));
    }

    /** "Jarvis": also the hint that helps speech-to-text spell the name. */
    private static String wakeWord(Voice voice) {
        String word = voice.wakeWord() == null || voice.wakeWord().isBlank() ? "jarvis" : voice.wakeWord().strip();
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    private static String mode(Engine engine, Optional<?> serverEngine) {
        if (serverEngine.isPresent()) {
            return VoiceServices.SERVER;
        }
        return engine == Engine.BROWSER ? VoiceServices.BROWSER : VoiceServices.OFF;
    }
}
