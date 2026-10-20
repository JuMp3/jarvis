package dev.jarvis.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration under the {@code jarvis} prefix (see application.yml).
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisProperties(Providers providers, Agent agent, Voice voice) {

    /**
     * @param enabled         provider ids offered to the user (anthropic, openai, ollama)
     * @param defaultProvider provider used when the client does not choose one
     */
    public record Providers(List<String> enabled, String defaultProvider) {
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
     * Voice settings. Speech-to-text and text-to-speech currently use the OpenAI audio API.
     */
    public record Voice(boolean enabled, String openaiApiKey, String language, String sttModel,
                        String ttsModel, String ttsVoice, String ttsInstructions) {

        public boolean configured() {
            return enabled && openaiApiKey != null && !openaiApiKey.isBlank();
        }
    }
}
