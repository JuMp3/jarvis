package dev.jarvis.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration under the {@code jarvis} prefix (see application.yml).
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisProperties(Providers providers, Agent agent, Voice voice) {

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
