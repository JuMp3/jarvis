package dev.jarvis.voice;

/**
 * Speech recognition: the OpenAI audio API, and the servers that speak it (whisper.cpp on this machine).
 */
public interface SpeechToText {

    /**
     * @param audio    recorded audio as sent by the browser
     * @param mimeType e.g. "audio/wav"
     * @param context  what was said just before, when the audio continues a phrase transcribed in pieces; may be
     *                 blank. It keeps the words and names of the piece consistent with the previous ones
     * @return the transcript, possibly empty
     */
    String transcribe(byte[] audio, String mimeType, String context);

    default String transcribe(byte[] audio, String mimeType) {
        return transcribe(audio, mimeType, "");
    }
}
