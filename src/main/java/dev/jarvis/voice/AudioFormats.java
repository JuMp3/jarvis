package dev.jarvis.voice;

/**
 * Detects the container format of recorded audio from its first bytes, so the client does not have
 * to announce it (Chrome records WebM, Safari records MP4).
 */
public final class AudioFormats {

    public static final String DEFAULT_MIME = "audio/webm";

    private AudioFormats() {
    }

    public static String detectMime(byte[] data) {
        if (data == null || data.length < 4) {
            return DEFAULT_MIME;
        }
        if (matches(data, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return "audio/webm";
        }
        if (matches(data, 0, 'O', 'g', 'g', 'S')) {
            return "audio/ogg";
        }
        if (data.length >= 8 && matches(data, 4, 'f', 't', 'y', 'p')) {
            return "audio/mp4";
        }
        if (matches(data, 0, 'R', 'I', 'F', 'F')) {
            return "audio/wav";
        }
        if (matches(data, 0, 'I', 'D', '3') || ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0)) {
            return "audio/mpeg";
        }
        return DEFAULT_MIME;
    }

    private static boolean matches(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
