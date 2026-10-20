package dev.jarvis.voice;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jarvis.config.JarvisProperties;

@Configuration(proxyBeanMethods = false)
public class VoiceConfig {

    @Bean
    VoiceServices voiceServices(JarvisProperties properties) {
        JarvisProperties.Voice voice = properties.voice();
        if (voice == null || !voice.configured()) {
            return VoiceServices.disabled();
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        OpenAiAudioClient client = new OpenAiAudioClient(http, OpenAiAudioClient.DEFAULT_BASE_URI,
                new OpenAiAudioClient.Settings(voice.openaiApiKey(), voice.language(), voice.sttModel(),
                        voice.ttsModel(), voice.ttsVoice(), voice.ttsInstructions()));
        return new VoiceServices(Optional.of(client), Optional.of(client));
    }
}
