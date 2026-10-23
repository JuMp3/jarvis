package dev.jarvis.voice;

/** The local voice cannot run (not installed, failed, still starting): the message is for the user, in their language. */
public class LocalVoiceException extends VoiceException {

    public LocalVoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
