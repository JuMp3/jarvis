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
            local = new LocalVoice(voice.local(), voice.language());
        }
        Optional<SpeechToText> stt = switch (voice.stt()) {
            case OPENAI -> openAi(voice, "jarvis.voice.stt", http).map(SpeechToText.class::cast);
            case LOCAL -> Optional.of(local.speechToText(http, settings(voice, voice.openai())));
            case BROWSER, OFF -> Optional.empty();
        };
        Optional<TextToSpeech> tts = switch (voice.tts()) {
            case OPENAI -> openAi(voice, "jarvis.voice.tts", http).map(TextToSpeech.class::cast);
            case LOCAL -> Optional.of(local.textToSpeech());
            case BROWSER, OFF -> Optional.empty();
        };
        return new VoiceServices(stt, tts, mode(voice.stt(), stt), mode(voice.tts(), tts), voice.language(),
                wakeWord(voice));
    }

    /** The local engines end with the application, also at the restart that applies new settings. */
    @PreDestroy
    void close() {
        if (local != null) {
            local.close();
        }
    }

    /** Empty without a key: the voice is off in that direction. */
    private static Optional<OpenAiAudioClient> openAi(Voice voice, String property, HttpClient http) {
        AudioEndpoint endpoint = voice.openai();
        if (endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            log.warn("{}=openai but no OpenAI API key is configured: voice disabled in this direction", property);
            return Optional.empty();
        }
        return Optional.of(new OpenAiAudioClient(http, URI.create(endpoint.baseUrl()), settings(voice, endpoint)));
    }

    /** For the local whisper.cpp server only the language and the prompt matter: it has one model loaded. */
    private static OpenAiAudioClient.Settings settings(Voice voice, AudioEndpoint endpoint) {
        return new OpenAiAudioClient.Settings(endpoint.apiKey(), voice.language(), endpoint.sttModel(),
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
