package dev.jarvis.llm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;

/**
 * One {@link ChatClient} per enabled provider, all sharing the same system prompt and memory,
 * so switching provider mid-conversation keeps the history.
 */
@Component
public class ProviderRouter {

    private final Map<ProviderId, ChatClient> clients;
    private final ProviderId defaultProvider;
    private final Map<String, String> voiceModels;

    public ProviderRouter(Map<String, ChatModel> chatModels, ChatMemory chatMemory, JarvisProperties properties) {
        Map<String, String> beanClasses = new LinkedHashMap<>();
        chatModels.forEach((name, model) -> beanClasses.put(name, model.getClass().getSimpleName()));

        Map<ProviderId, String> selected = ProviderId.selectEnabled(beanClasses, properties.providers().enabled());
        if (selected.isEmpty()) {
            throw new IllegalStateException("No LLM provider available. Check jarvis.providers.enabled "
                    + "(configured: " + properties.providers().enabled() + ", chat model beans: "
                    + beanClasses.keySet() + ")");
        }

        Map<ProviderId, ChatClient> built = new EnumMap<>(ProviderId.class);
        selected.forEach((provider, beanName) -> built.put(provider,
                ChatClient.builder(chatModels.get(beanName))
                        .defaultSystem(properties.agent().systemPrompt())
                        // Inside the tool loop, so tool calls and results are remembered across turns
                        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                                .order(ToolCallingAdvisor.DEFAULT_ORDER + 1)
                                .build())
                        .build()));
        this.clients = Collections.unmodifiableMap(built);

        this.voiceModels = properties.providers().voiceModels() == null ? Map.of() : properties.providers().voiceModels();
        this.defaultProvider = ProviderId.parse(properties.providers().defaultProvider())
                .filter(clients::containsKey)
                .orElse(selected.keySet().iterator().next());
    }

    /** Client for the requested provider, falling back to the default one. */
    public ChatClient client(ProviderId provider) {
        return clients.getOrDefault(provider, clients.get(defaultProvider));
    }

    /** Resolves a provider id coming from the client, falling back to the default one. */
    public ProviderId resolve(String requested) {
        return ProviderId.parse(requested).filter(clients::containsKey).orElse(defaultProvider);
    }

    /** Model configured for spoken turns with this provider, if any. */
    public Optional<String> voiceModel(ProviderId provider) {
        return Optional.ofNullable(voiceModels.get(resolve(provider.id()).id())).filter(model -> !model.isBlank());
    }

    public ProviderId defaultProvider() {
        return defaultProvider;
    }

    public List<String> availableIds() {
        return clients.keySet().stream().map(ProviderId::id).toList();
    }
}
