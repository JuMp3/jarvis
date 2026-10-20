package dev.jarvis.voice;

import java.util.Optional;

/**
 * Voice engines that are actually configured. Empty when voice is disabled or has no API key,
 * in which case Jarvis works in text-only mode.
 */
public record VoiceServices(Optional<SpeechToText> speechToText, Optional<TextToSpeech> textToSpeech) {

    public static VoiceServices disabled() {
        return new VoiceServices(Optional.empty(), Optional.empty());
    }

    public boolean enabled() {
        return speechToText.isPresent() && textToSpeech.isPresent();
    }
}
