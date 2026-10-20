package dev.jarvis.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ProviderIdTest {

    @Test
    void parsesCaseInsensitively() {
        assertEquals(Optional.of(ProviderId.OPENAI), ProviderId.parse(" OpenAI "));
        assertTrue(ProviderId.parse("gemini").isEmpty());
        assertTrue(ProviderId.parse(null).isEmpty());
    }

    @Test
    void recognisesSpringAiBeans() {
        assertEquals(Optional.of(ProviderId.ANTHROPIC), ProviderId.fromBean("anthropicChatModel", "AnthropicChatModel"));
        assertEquals(Optional.of(ProviderId.OPENAI), ProviderId.fromBean("openAiChatModel", "OpenAiChatModel"));
        assertEquals(Optional.of(ProviderId.OLLAMA), ProviderId.fromBean("ollamaChatModel", "OllamaChatModel"));
        assertTrue(ProviderId.fromBean("myChatModel", "CustomChatModel").isEmpty());
    }

    @Test
    void selectsOnlyEnabledProvidersInConfiguredOrder() {
        Map<String, String> beans = new LinkedHashMap<>();
        beans.put("anthropicChatModel", "AnthropicChatModel");
        beans.put("openAiChatModel", "OpenAiChatModel");
        beans.put("ollamaChatModel", "OllamaChatModel");

        Map<ProviderId, String> selected = ProviderId.selectEnabled(beans, List.of("ollama", "anthropic", "unknown"));

        assertEquals(List.of(ProviderId.OLLAMA, ProviderId.ANTHROPIC), List.copyOf(selected.keySet()));
        assertEquals("ollamaChatModel", selected.get(ProviderId.OLLAMA));
    }

    @Test
    void ignoresEnabledProvidersWithoutBean() {
        Map<String, String> beans = Map.of("anthropicChatModel", "AnthropicChatModel");
        assertEquals(List.of(ProviderId.ANTHROPIC),
                List.copyOf(ProviderId.selectEnabled(beans, List.of("openai", "anthropic")).keySet()));
    }
}
