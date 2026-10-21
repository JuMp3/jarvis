package dev.jarvis.voice;

public class VoiceException extends RuntimeException {

    private final int statusCode;

    public VoiceException(String message) {
        this(message, 0);
    }

    public VoiceException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public VoiceException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    /** HTTP status returned by the voice service, 0 when there was no response. */
    public int statusCode() {
        return statusCode;
    }
}
