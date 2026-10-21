package dev.jarvis.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

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

    @Bean
    VoiceServices voiceServices(JarvisProperties properties) {
        Voice voice = properties.voice();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Optional<OpenAiAudioClient> stt = serverClient(voice, voice.stt(), "jarvis.voice.stt", http);
        Optional<OpenAiAudioClient> tts = serverClient(voice, voice.tts(), "jarvis.voice.tts", http);
        return new VoiceServices(stt.map(SpeechToText.class::cast), tts.map(TextToSpeech.class::cast),
                mode(voice.stt(), stt), mode(voice.tts(), tts), voice.language());
    }

    /** Client for an engine that runs behind the server; empty for browser, off, or OpenAI without key. */
    private static Optional<OpenAiAudioClient> serverClient(Voice voice, Engine engine, String property, HttpClient http) {
        AudioEndpoint endpoint = switch (engine) {
            case OPENAI -> voice.openai();
            case LOCAL -> voice.local();
            case BROWSER, OFF -> null;
        };
        if (endpoint == null) {
            return Optional.empty();
        }
        if (engine == Engine.OPENAI && (endpoint.apiKey() == null || endpoint.apiKey().isBlank())) {
            log.warn("{}=openai but no OpenAI API key is configured: voice disabled in this direction", property);
            return Optional.empty();
        }
        return Optional.of(new OpenAiAudioClient(http, URI.create(endpoint.baseUrl()),
                new OpenAiAudioClient.Settings(endpoint.apiKey(), voice.language(), endpoint.sttModel(),
                        endpoint.ttsModel(), endpoint.ttsVoice(), endpoint.ttsInstructions())));
    }

    private static String mode(Engine engine, Optional<?> serverClient) {
        if (serverClient.isPresent()) {
            return VoiceServices.SERVER;
        }
        return engine == Engine.BROWSER ? VoiceServices.BROWSER : VoiceServices.OFF;
    }
}
