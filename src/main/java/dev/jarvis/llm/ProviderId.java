package dev.jarvis.llm;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM providers Jarvis knows how to route to. Pure logic, no Spring dependency.
 */
public enum ProviderId {

    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    OLLAMA("ollama");

    private final String id;

    ProviderId(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Parses a user/config value such as "Anthropic" or " openai ". */
    public static Optional<ProviderId> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ProviderId provider : values()) {
            if (provider.id.equals(normalized)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /**
     * Identifies the provider behind a Spring bean, e.g. "openAiChatModel" or a class named
     * "AnthropicChatModel". Matching on names avoids compile-time coupling to provider classes.
     */
    public static Optional<ProviderId> fromBean(String beanName, String className) {
        String haystack = (nullToEmpty(beanName) + " " + nullToEmpty(className))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", "");
        for (ProviderId provider : values()) {
            if (haystack.contains(provider.id)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /**
     * Keeps the beans whose provider is enabled, one bean per provider, ordered as in {@code enabled}.
     *
     * @param beanClassNames bean name to class simple name
     * @param enabled        configured provider ids
     * @return provider to bean name
     */
    public static Map<ProviderId, String> selectEnabled(Map<String, String> beanClassNames,
                                                        Collection<String> enabled) {
        List<ProviderId> wanted = enabled.stream()
                .map(ProviderId::parse)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        Map<ProviderId, String> found = new LinkedHashMap<>();
        beanClassNames.forEach((bean, cls) -> fromBean(bean, cls)
                .ifPresent(provider -> found.putIfAbsent(provider, bean)));

        Map<ProviderId, String> result = new LinkedHashMap<>();
        for (ProviderId provider : wanted) {
            if (found.containsKey(provider)) {
                result.put(provider, found.get(provider));
            }
        }
        return result;
    }

    /** Ids as shown to the client. */
    public static List<String> ids(Set<ProviderId> providers) {
        return providers.stream().map(ProviderId::id).collect(Collectors.toList());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
