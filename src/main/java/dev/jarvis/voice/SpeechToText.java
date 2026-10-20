package dev.jarvis.voice;

/**
 * Speech recognition. Implementations: OpenAI today; whisper.cpp or similar locally later.
 */
public interface SpeechToText {

    /**
     * @param audio    recorded audio as sent by the browser
     * @param mimeType e.g. "audio/webm"
     * @return the transcript, possibly empty
     */
    String transcribe(byte[] audio, String mimeType);
}
