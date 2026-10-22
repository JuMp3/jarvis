package dev.jarvis.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.Voice;
import dev.jarvis.config.JarvisProperties.Voice.AudioEndpoint;
import dev.jarvis.config.JarvisProperties.Voice.Engine;
import dev.jarvis.config.JarvisProperties.Voice.Local;

class VoiceConfigTest {

    private static VoiceServices services(Engine stt, Engine tts, String openAiKey) {
        AudioEndpoint openai = new AudioEndpoint("https://api.openai.com/v1/", openAiKey, "stt", "tts", "onyx", "");
        Local local = new Local("missing-folder", "small", "it_IT-paola-medium", 1); // engines not installed: still configured
        Voice voice = new Voice(stt, tts, "it", openai, local, "jarvis");
        return new VoiceConfig().voiceServices(new JarvisProperties(null, null, voice, null, null, null, null, null, null, null));
    }

    @Test
    void localEnginesRunOnTheServerWithoutKey() {
        VoiceServices voice = services(Engine.LOCAL, Engine.LOCAL, "");

        assertThat(voice.sttMode()).isEqualTo(VoiceServices.SERVER);
        assertThat(voice.ttsMode()).isEqualTo(VoiceServices.SERVER);
        assertThat(voice.speechToText()).isPresent();
        assertThat(voice.textToSpeech()).isPresent();
    }

    @Test
    void browserAndOffLeaveTheServerOut() {
        VoiceServices voice = services(Engine.BROWSER, Engine.OFF, "sk-test");

        assertThat(voice.sttMode()).isEqualTo(VoiceServices.BROWSER);
        assertThat(voice.ttsMode()).isEqualTo(VoiceServices.OFF);
        assertThat(voice.speechToText()).isEmpty();
        assertThat(voice.textToSpeech()).isEmpty();
    }

    @Test
    void openAiNeedsAKey() {
        assertThat(services(Engine.OPENAI, Engine.OPENAI, " ").ttsMode()).isEqualTo(VoiceServices.OFF);
        assertThat(services(Engine.OPENAI, Engine.LOCAL, "sk-test").sttMode()).isEqualTo(VoiceServices.SERVER);
    }
}
