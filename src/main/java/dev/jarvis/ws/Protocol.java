package dev.jarvis.ws;

import java.util.List;

/**
 * WebSocket messages. Keep in sync with static/app.js and the table in docs/ARCHITECTURE.md.
 */
public final class Protocol {

    private Protocol() {
    }

    // ---- client -> server -------------------------------------------------------------------

    /** Union of every client message; unused fields are null. */
    public record ClientMessage(String type, String text, String provider, Boolean speak,
                                String id, Boolean approved) {
    }

    // ---- server -> client -------------------------------------------------------------------

    public record Hello(String type, List<String> providers, String defaultProvider, boolean voiceEnabled) {
    }

    public record State(String type, String state) {
    }

    public record Transcript(String type, String text) {
    }

    public record Token(String type, String text) {
    }

    public record Audio(String type, int seq, String mime, String data) {
    }

    public record ApprovalPrompt(String type, String id, String description) {
    }

    public record Done(String type) {
    }

    public record ErrorMessage(String type, String message) {
    }

    public static Hello hello(List<String> providers, String defaultProvider, boolean voiceEnabled) {
        return new Hello("hello", providers, defaultProvider, voiceEnabled);
    }

    public static State state(String state) {
        return new State("state", state);
    }

    public static Transcript transcript(String text) {
        return new Transcript("transcript", text);
    }

    public static Token token(String text) {
        return new Token("token", text);
    }

    public static Audio audio(int seq, String mime, String base64Data) {
        return new Audio("audio", seq, mime, base64Data);
    }

    public static ApprovalPrompt approvalRequest(String id, String description) {
        return new ApprovalPrompt("approval-request", id, description);
    }

    public static Done done() {
        return new Done("done");
    }

    public static ErrorMessage error(String message) {
        return new ErrorMessage("error", message);
    }
}
