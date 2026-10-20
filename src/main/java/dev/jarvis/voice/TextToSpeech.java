package dev.jarvis.voice;

/**
 * Speech synthesis. Implementations: OpenAI today; Piper or similar locally later.
 */
public interface TextToSpeech {

    record SpeechAudio(byte[] data, String mimeType) {
    }

    SpeechAudio synthesize(String text);
}
