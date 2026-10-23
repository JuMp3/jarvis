package dev.jarvis.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties.Voice.Local;

/** The real engines: runs only where the local voice is prepared (desktop/prepare-voice.ps1), skipped elsewhere. */
class LocalVoiceTest {

    private static final Path HOME = Path.of(System.getenv().getOrDefault("JARVIS_VOICE_HOME",
            "desktop/src-tauri/resources/voice"));

    @Test
    void piperSpeaksAndWhisperUnderstandsIt() {
        assumeTrue(Files.isDirectory(HOME.resolve("models")), "local voice not prepared in " + HOME.toAbsolutePath());
        try (LocalVoice voice = new LocalVoice(new Local(HOME.toAbsolutePath().toString(), "base", Map.of("it", "it_IT-paola-medium"), 4), "it")) {
            TextToSpeech tts = voice.textToSpeech();
            SpeechToText stt = voice.speechToText(HttpClient.newHttpClient(),
                    new OpenAiAudioClient.Settings("", "it", "whisper-1", null, null, null, "Jarvis"));

            TextToSpeech.SpeechAudio audio = tts.synthesize("Domani a Milano ci sarà il sole.");
            assertThat(audio.mimeType()).isEqualTo("audio/wav");
            assertThat(new String(Arrays.copyOf(audio.data(), 4), StandardCharsets.US_ASCII)).isEqualTo("RIFF");

            assertThat(stt.transcribe(audio.data(), "audio/wav")).containsIgnoringCase("Milano");
        }
    }
}
