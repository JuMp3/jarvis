package dev.jarvis.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClaudeCodeEventsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode toolUse(String name, String input) {
        return JSON.readTree("""
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"%s","input":%s}]}}"""
                .formatted(name, input));
    }

    @Test
    void describesToolCallsInWords() {
        assertThat(ClaudeCodeEvents.progress(toolUse("Edit", "{\"file_path\":\"C:\\\\repo\\\\src\\\\App.java\"}"), "it"))
                .contains("Modifica App.java");
        assertThat(ClaudeCodeEvents.progress(toolUse("Read", "{\"file_path\":\"/repo/pom.xml\"}"), "it"))
                .contains("Legge pom.xml");
        assertThat(ClaudeCodeEvents.progress(toolUse("Bash", "{\"command\":\"./mvnw  -q\\n verify\"}"), "it"))
                .contains("Esegue ./mvnw -q verify");
        assertThat(ClaudeCodeEvents.progress(toolUse("Read", "{\"file_path\":\"/repo/pom.xml\"}"), "en"))
                .contains("Reads pom.xml");
        assertThat(ClaudeCodeEvents.progress(JSON.readTree("""
                {"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]}}"""), "it")).isEmpty();
    }

    @Test
    void capturesThePlanProposedThroughExitPlanMode() {
        assertThat(ClaudeCodeEvents.plan(toolUse("ExitPlanMode", "{\"plan\":\"1. Aggiungere README\"}")))
                .contains("1. Aggiungere README");
        assertThat(ClaudeCodeEvents.plan(toolUse("Read", "{\"file_path\":\"a\"}"))).isEmpty();
    }

    @Test
    void readsTheFinalOutcome() {
        ClaudeCodeEvents.Outcome outcome = ClaudeCodeEvents.outcome(JSON.readTree("""
                {"type":"result","subtype":"success","is_error":false,"result":"Fatto.","session_id":"s-1",
                 "total_cost_usd":0.42,"usage":{"input_tokens":10,"cache_creation_input_tokens":100,
                 "cache_read_input_tokens":1000,"output_tokens":50}}""")).orElseThrow();

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.text()).isEqualTo("Fatto.");
        assertThat(outcome.sessionId()).isEqualTo("s-1");
        assertThat(outcome.costUsd()).isEqualTo(0.42);
        assertThat(outcome.inputTokens()).isEqualTo(1110);
        assertThat(outcome.outputTokens()).isEqualTo(50);
        assertThat(ClaudeCodeEvents.outcome(toolUse("Read", "{}"))).isEmpty();
    }
}
