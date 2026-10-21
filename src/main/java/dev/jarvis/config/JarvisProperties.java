package dev.jarvis.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration under the {@code jarvis} prefix (see application.yml).
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisProperties(Providers providers, Agent agent, Voice voice, Tasks tasks,
                               Map<String, Specialist> agents, Documents documents, Desktop desktop, Mcp mcp) {

    /** @param servers MCP servers Jarvis uses, by connection name of spring.ai.mcp.client */
    public record Mcp(Map<String, McpServer> servers) {
    }

    /**
     * Which tools of an MCP server are exposed; the others are not.
     *
     * @param label      name shown in approval requests, e.g. "GitHub"
     * @param headers    added to every request to the server (e.g. Authorization)
     * @param readTools  for the specialists that list "mcp:&lt;server&gt;"
     * @param writeTools for Jarvis only, each call after the user's approval
     */
    public record McpServer(String label, Map<String, String> headers, List<String> readTools,
                            List<String> writeTools) {
    }

    /** @param folders folders the documents specialist may read (created if missing) */
    public record Documents(List<String> folders) {
    }

    /** @param apps catalog of applications Jarvis may start, always after the user's approval */
    public record Desktop(Map<String, DesktopApp> apps) {
    }

    /**
     * @param description shown to the model and in the approval request
     * @param command     executable and arguments, started without a shell; "{target}" is replaced by a file
     *                    or folder the model passes (it must exist)
     */
    public record DesktopApp(String description, List<String> command) {
    }

    /**
     * Background tasks run by specialists.
     *
     * @param maxParallel tasks running at the same time; the others wait in the queue
     * @param timeout          a task still running after this is stopped and marked failed (time spent
     *                         waiting for the user's approval does not count)
     * @param approvalTimeout  how long a task waits for the user's approval before it is denied
     */
    public record Tasks(int maxParallel, Duration timeout, Duration approvalTimeout) {
    }

    /**
     * A specialist Jarvis can delegate to (key = id used by the orchestrator).
     *
     * @param type            "chat" (default: one model call with read-only tools) or "claude-code"
     * @param description     what it is good at: the orchestrator reads it to choose
     * @param tools           chat only: tool groups "time", "web-search" (Anthropic server-side search)
     * @param maxSearches     chat only: web searches allowed per task (budget)
     * @param maxTokens       chat only: output tokens per answer
     * @param workspaces      claude-code only: folders whose git repositories it may work on
     * @param maxBudgetUsd    claude-code only: spending limit per task, plan included
     * @param allowedCommands claude-code only: shell commands it may run without asking, as Claude Code
     *                        permission rules (e.g. "Bash(git commit:*)"); git push is always denied
     * @param timeout         overrides jarvis.tasks.timeout for this specialist
     * @param command         claude-code only: executable to run, default "claude"
     */
    public record Specialist(String type, String description, String provider, String model, String systemPrompt,
                             List<String> tools, Integer maxSearches, Integer maxTokens,
                             List<String> workspaces, Double maxBudgetUsd, List<String> allowedCommands,
                             Duration timeout, String command) {
    }

    /**
     * @param enabled         provider ids offered to the user (anthropic, openai, ollama)
     * @param defaultProvider provider used when the client does not choose one
     * @param voiceModels     provider id to the model used for spoken turns; may be null
     */
    public record Providers(List<String> enabled, String defaultProvider, Map<String, String> voiceModels) {
    }

    /**
     * @param memoryMaxMessages messages kept in the conversation window
     * @param approvalTimeout   how long a tool waits for the user's approval before it is denied
     * @param notesDir          sandbox folder for the note tools
     * @param systemPrompt      system prompt shared by every provider
     */
    public record Agent(int memoryMaxMessages, Duration approvalTimeout, String notesDir, String systemPrompt) {
    }

    /**
     * Voice settings: which engine turns speech into text ({@code stt}) and text into speech ({@code tts}).
     *
     * @param language ISO 639-1 code passed to the engines (e.g. "it")
     * @param openai   OpenAI audio API
     * @param local    OpenAI-compatible server on this machine (Speaches, see compose.yaml)
     */
    public record Voice(Engine stt, Engine tts, String language, AudioEndpoint openai, AudioEndpoint local) {

        public enum Engine {
            /** OpenAI audio API, needs an API key with credit. */
            OPENAI,
            /** Local OpenAI-compatible server. */
            LOCAL,
            /** Web Speech API in the browser: the server is not involved. */
            BROWSER,
            /** Disabled. */
            OFF
        }

        /**
         * @param apiKey          may be blank for local servers
         * @param ttsInstructions tone of voice, only supported by OpenAI; may be blank
         */
        public record AudioEndpoint(String baseUrl, String apiKey, String sttModel, String ttsModel,
                                    String ttsVoice, String ttsInstructions) {
        }
    }
}
