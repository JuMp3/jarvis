package dev.jarvis.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration under the {@code jarvis} prefix (see application.yml).
 *
 * <p>{@code language}: ISO 639-1 code of the user's language ("it", "en"; Settings → Profilo), for the interface,
 * the voice engines and dates.
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisProperties(Providers providers, Agent agent, Voice voice, Tasks tasks,
                               Map<String, Specialist> agents, Documents documents, Desktop desktop, Mcp mcp,
                               Mail mail, Memory memory, String language) {

    /** @param home folder with model.onnx and tokenizer.json of the embedding model (desktop/prepare-memory.ps1) */
    public record Memory(String home) {
    }

    /**
     * An IMAP/SMTP mailbox (Gmail, iCloud, Yahoo, Libero... with an app password).
     *
     * @param imapServer "host" or "host:port"; empty to derive it from the address for well-known providers
     * @param smtpServer "host" or "host:port"; same
     */
    public record Mail(boolean enabled, String address, String password, String imapServer, String smtpServer) {
    }

    /**
     * @param servers  MCP servers Jarvis uses, by connection name of spring.ai.mcp.client
     * @param nodeHome the Node of the desktop app with the npx servers already installed (NpxCommand); blank: npx
     */
    public record Mcp(Map<String, McpServer> servers, String nodeHome) {
    }

    /**
     * Which tools of an MCP server are exposed; the others are not.
     *
     * @param label      name shown in approval requests, e.g. "GitHub"
     * @param headers    added to every request to the server (e.g. Authorization)
     * @param readTools  for the specialists that list "mcp:&lt;server&gt;"
     * @param writeTools for Jarvis only, each call after the user's approval
     */
    /** @param token sent as "Authorization: Bearer", for servers such as GitHub (set from Settings) */
    public record McpServer(String label, Map<String, String> headers, String token, List<String> readTools,
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
     * @param routine         chat only: runs the routines, and nothing else (Jarvis does not delegate to it); may
     *                        get the tool groups "mcp:*" and "write", every write behind the user's approval
     */
    public record Specialist(String type, String description, String provider, String model, String systemPrompt,
                             List<String> tools, Integer maxSearches, Integer maxTokens,
                             List<String> workspaces, Double maxBudgetUsd, List<String> allowedCommands,
                             Duration timeout, String command, Boolean routine) {
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
     * @param userName          how the user wants to be called (Settings → Profilo); may be blank
     */
    public record Agent(int memoryMaxMessages, Duration approvalTimeout, String notesDir, String systemPrompt,
                        String userName) {
    }

    /**
     * Voice settings: which engine turns speech into text ({@code stt}) and text into speech ({@code tts}).
     *
     * @param openai   OpenAI audio API
     * @param groq     Groq, the same API: speech-to-text only (Whisper large)
     * @param azure    Azure neural voices: text-to-speech only
     * @param local    engines run by Jarvis on this machine (whisper.cpp and Piper, see LocalVoice)
     */
    public record Voice(Engine stt, Engine tts, AudioEndpoint openai, AudioEndpoint groq, Azure azure, Local local,
                        String wakeWord) {

        public enum Engine {
            /** OpenAI audio API, needs an API key with credit. */
            OPENAI,
            /** Groq, with the OpenAI audio API: speech-to-text only, needs a Groq API key. */
            GROQ,
            /** Azure neural voices: text-to-speech only, needs the key and region of a Speech resource. */
            AZURE,
            /** whisper.cpp and Piper on this machine. */
            LOCAL,
            /** Web Speech API in the browser: the server is not involved. */
            BROWSER,
            /** Disabled. */
            OFF
        }

        /**
         * @param region e.g. italynorth, where the Speech resource is
         * @param voices voice by language ("it", "en"), e.g. it-IT-DiegoNeural
         * @param rate   pace in percent over the voice's own, e.g. "10"; blank: its own
         */
        public record Azure(String key, String region, Map<String, String> voices, String rate) {
        }

        /**
         * @param apiKey          may be blank for local servers
         * @param ttsInstructions tone of voice, only supported by OpenAI; may be blank
         */
        public record AudioEndpoint(String baseUrl, String apiKey, String sttModel, String ttsModel,
                                    String ttsVoice, String ttsInstructions) {
        }

        /**
         * @param home     folder with whisper/, piper/ and models/ (desktop/prepare-voice.ps1)
         * @param sttModel "small" (more precise) or "base" (faster)
         * @param ttsVoices Piper voice by language ("it", "en"), a file of models/ without .onnx
         * @param threads  for whisper.cpp; null: 4 (or fewer processors)
         */
        public record Local(String home, String sttModel, Map<String, String> ttsVoices, Integer threads) {
        }
    }
}
