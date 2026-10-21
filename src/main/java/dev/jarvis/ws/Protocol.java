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

    /**
     * @param stt      who transcribes speech: "server" (send recorded audio), "browser" (Web Speech API) or "off"
     * @param tts      who speaks answers: "server" (audio messages), "browser" (Web Speech API) or "off"
     * @param language ISO 639-1 code for the browser engines
     */
    public record Hello(String type, List<String> providers, String defaultProvider, String stt, String tts,
                        String language) {
    }

    public record History(String type, List<HistoryEntry> messages) {
    }

    /** @param role "user" or "assistant" */
    public record HistoryEntry(String role, String text) {
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

    public static Hello hello(List<String> providers, String defaultProvider, String stt, String tts,
                              String language) {
        return new Hello("hello", providers, defaultProvider, stt, tts, language);
    }

    public static History history(List<HistoryEntry> messages) {
        return new History("history", messages);
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
