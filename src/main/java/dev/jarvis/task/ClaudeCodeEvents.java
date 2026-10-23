package dev.jarvis.task;

import static dev.jarvis.i18n.Text.text;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Reads the stream-json events of Claude Code ({@code claude -p --output-format stream-json}). Pure logic.
 */
final class ClaudeCodeEvents {

    private static final int COMMAND_PREVIEW = 70;

    /** Final event of a run. */
    record Outcome(boolean success, String text, String sessionId, double costUsd, int inputTokens,
                   int outputTokens, String subtype) {
    }

    private ClaudeCodeEvents() {
    }

    /** What the coder is doing, in words for the Squadra panel; empty for events that are not tool calls. */
    static Optional<String> progress(JsonNode event, String language) {
        if (!"assistant".equals(event.path("type").asString(""))) {
            return Optional.empty();
        }
        for (JsonNode content : event.path("message").path("content")) {
            if ("tool_use".equals(content.path("type").asString(""))) {
                return Optional.of(describeTool(content.path("name").asString(""), content.path("input"), language));
            }
        }
        return Optional.empty();
    }

    /** The plan proposed through ExitPlanMode, if this event carries it. */
    static Optional<String> plan(JsonNode event) {
        if (!"assistant".equals(event.path("type").asString(""))) {
            return Optional.empty();
        }
        for (JsonNode content : event.path("message").path("content")) {
            if ("tool_use".equals(content.path("type").asString(""))
                    && "ExitPlanMode".equals(content.path("name").asString(""))) {
                String plan = content.path("input").path("plan").asString("");
                return plan.isBlank() ? Optional.empty() : Optional.of(plan);
            }
        }
        return Optional.empty();
    }

    static Optional<Outcome> outcome(JsonNode event) {
        if (!"result".equals(event.path("type").asString(""))) {
            return Optional.empty();
        }
        JsonNode usage = event.path("usage");
        int input = usage.path("input_tokens").asInt(0) + usage.path("cache_creation_input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0);
        return Optional.of(new Outcome(!event.path("is_error").asBoolean(false), event.path("result").asString(""),
                event.path("session_id").asString(""), event.path("total_cost_usd").asDouble(0),
                input, usage.path("output_tokens").asInt(0), event.path("subtype").asString("")));
    }

    static String describeTool(String name, JsonNode input, String language) {
        return switch (name) {
            case "Read" -> text("Legge %s", "Reads %s").in(language, fileName(input, language));
            case "Edit", "MultiEdit", "Write", "NotebookEdit" ->
                    text("Modifica %s", "Edits %s").in(language, fileName(input, language));
            case "Glob", "Grep" -> text("Cerca nel codice", "Searches the code").in(language);
            case "Bash", "PowerShell" ->
                    text("Esegue %s", "Runs %s").in(language, preview(input.path("command").asString("")));
            case "TodoWrite" -> text("Aggiorna la lista dei passi", "Updates the list of steps").in(language);
            case "ExitPlanMode" -> text("Propone il piano", "Proposes the plan").in(language);
            case "WebFetch", "WebSearch" -> text("Consulta la documentazione", "Reads the documentation").in(language);
            default -> text("Usa %s", "Uses %s").in(language, name);
        };
    }

    private static String fileName(JsonNode input, String language) {
        String path = input.path("file_path").asString(input.path("notebook_path").asString(""));
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.isBlank() ? text("un file", "a file").in(language) : path.substring(slash + 1);
    }

    private static String preview(String command) {
        String oneLine = command.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= COMMAND_PREVIEW ? oneLine : oneLine.substring(0, COMMAND_PREVIEW) + "…";
    }
}
