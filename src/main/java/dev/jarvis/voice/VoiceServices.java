package dev.jarvis.voice;

import java.util.Optional;

/**
 * Voice engines that run on the server, plus how the client should handle each direction.
 *
 * @param sttMode "server", "browser" or "off", as sent to the client
 * @param ttsMode "server", "browser" or "off", as sent to the client
 */
public record VoiceServices(Optional<SpeechToText> speechToText, Optional<TextToSpeech> textToSpeech,
                            String sttMode, String ttsMode, String language, String wakeWord) {

    public static final String SERVER = "server";
    public static final String BROWSER = "browser";
    public static final String OFF = "off";
}
