package dev.jarvis.ws;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.jarvis.agent.ApprovalRequest;
import dev.jarvis.settings.SettingsService;
import dev.jarvis.task.AgentTask;

/**
 * WebSocket messages. Keep in sync with static/app.js and the table in docs/ARCHITECTURE.md.
 */
public final class Protocol {

    private Protocol() {
    }

    // ---- client -> server -------------------------------------------------------------------

    /** Union of every client message; unused fields are null. */
    /**
     * @param values  settings-save: setting name to value (text, or list of strings)
     * @param cleared settings-save: secrets to remove
     * @param name    settings-test: setting to check; value: the key typed, or empty for the one in use
     * @param server  account-login / account-verify: MCP server of the account
     * @param wake    voice-mode: true when hands-free requires the wake word
     */
    public record ClientMessage(String type, String text, String provider, Boolean speak,
                                String id, Boolean approved, Map<String, Object> values, List<String> cleared,
                                String name, String value, String server, Boolean wake) {
    }

    // ---- server -> client -------------------------------------------------------------------

    /**
     * @param stt      who transcribes speech: "server" (send recorded audio), "browser" (Web Speech API) or "off"
     * @param tts      who speaks answers: "server" (audio messages), "browser" (Web Speech API) or "off"
     * @param language ISO 639-1 code for the browser engines
     */
    /** @param setupRequired true until the wizard has been completed once */
    /** @param conversation the conversation of this connection: the page keeps it for the next ones */
    public record Hello(String type, List<String> providers, String defaultProvider, String stt, String tts,
                        String language, String wakeWord, boolean setupRequired, String conversation) {
    }

    public record Settings(String type, List<SettingsService.View> settings, boolean setupRequired) {
    }

    /** @param restarting true when the new settings are being applied with a restart */
    public record SettingsSaved(String type, boolean restarting) {
    }

    public record SettingsError(String type, String message) {
    }

    public record SettingsTestResult(String type, String name, boolean ok, String message) {
    }

    public record AccountResult(String type, String server, String message) {
    }

    public record SettingsSecret(String type, String name, String value) {
    }

    /** Every tab of the conversation empties its log. */
    public record HistoryCleared(String type) {
    }

    public record History(String type, List<HistoryEntry> messages) {
    }

    /** @param role "user" or "assistant" */
    public record HistoryEntry(String role, String text) {
    }

    public record State(String type, String state) {
    }

    /** A background task as shown in the activity panel; times are epoch milliseconds. */
    public record TaskView(String id, String agent, String title, String instructions, String workspace,
                           String status, String progress, String result, String error, Integer inputTokens,
                           Integer outputTokens, Double costUsd, Long createdAt, Long startedAt, Long finishedAt) {

        static TaskView of(AgentTask task) {
            return new TaskView(task.id(), task.agent(), task.title(), task.instructions(), task.workspace(),
                    task.status().name().toLowerCase(Locale.ROOT), task.progress(), task.result(), task.error(),
                    task.inputTokens(), task.outputTokens(), task.costUsd(), millis(task.createdAt()),
                    millis(task.startedAt()), millis(task.finishedAt()));
        }

        private static Long millis(Instant instant) {
            return instant == null ? null : instant.toEpochMilli();
        }
    }

    public record TaskUpdate(String type, TaskView task) {
    }

    public record Tasks(String type, List<TaskView> tasks) {
    }

    public record Transcript(String type, String text) {
    }

    /** A hands-free phrase not addressed to Jarvis: shown faintly, never sent to the model. */
    public record TranscriptIgnored(String type, String text) {
    }

    public record Token(String type, String text) {
    }

    public record Audio(String type, int seq, String mime, String data) {
    }

    /** @param agent and taskTitle are null when Jarvis itself asks */
    public record ApprovalPrompt(String type, String id, String description, String agent, String taskTitle) {
    }

    public record ApprovalClosed(String type, String id) {
    }

    public record Done(String type) {
    }

    public record ErrorMessage(String type, String message) {
    }

    public static Hello hello(List<String> providers, String defaultProvider, String stt, String tts,
                              String language, String wakeWord, boolean setupRequired, String conversation) {
        return new Hello("hello", providers, defaultProvider, stt, tts, language, wakeWord, setupRequired, conversation);
    }

    public static Settings settings(List<SettingsService.View> settings, boolean setupRequired) {
        return new Settings("settings", settings, setupRequired);
    }

    public static SettingsSaved settingsSaved(boolean restarting) {
        return new SettingsSaved("settings-saved", restarting);
    }

    public static SettingsError settingsError(String message) {
        return new SettingsError("settings-error", message);
    }

    public static SettingsTestResult settingsTestResult(String name, SettingsService.TestResult result) {
        return new SettingsTestResult("settings-test-result", name, result.ok(), result.message());
    }

    public static SettingsSecret settingsSecret(String name, String value) {
        return new SettingsSecret("settings-secret", name, value);
    }

    public static AccountResult accountResult(String server, String message) {
        return new AccountResult("account-result", server, message);
    }

    public static History history(List<HistoryEntry> messages) {
        return new History("history", messages);
    }

    public static TaskUpdate taskUpdate(AgentTask task) {
        return new TaskUpdate("task-update", TaskView.of(task));
    }

    public static HistoryCleared historyCleared() {
        return new HistoryCleared("history-cleared");
    }

    public static Tasks tasks(List<AgentTask> tasks) {
        return new Tasks("tasks", tasks.stream().map(TaskView::of).toList());
    }

    public static State state(String state) {
        return new State("state", state);
    }

    public static TranscriptIgnored transcriptIgnored(String text) {
        return new TranscriptIgnored("transcript-ignored", text);
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

    public static ApprovalPrompt approvalRequest(ApprovalRequest request) {
        return new ApprovalPrompt("approval-request", request.id(), request.description(), request.agent(),
                request.taskTitle());
    }

    public static ApprovalClosed approvalClosed(String id) {
        return new ApprovalClosed("approval-closed", id);
    }

    public static Done done() {
        return new Done("done");
    }

    public static ErrorMessage error(String message) {
        return new ErrorMessage("error", message);
    }
}
