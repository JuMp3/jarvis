package dev.jarvis.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;

import dev.jarvis.BaseTest;

class PostgresChatMemoryRepositoryTest extends BaseTest {

    @Autowired
    PostgresChatMemoryRepository repository;

    @Autowired
    ChatMemory chatMemory;

    private static final AssistantMessage.ToolCall CALL =
            new AssistantMessage.ToolCall("call-1", "function", "currentDateTime", "{}");
    private static final ToolResponseMessage.ToolResponse RESPONSE =
            new ToolResponseMessage.ToolResponse("call-1", "currentDateTime", "\"mercoledì 21 ottobre 2026, 09:30\"");

    private static List<Message> turnWithTool() {
        return List.of(
                UserMessage.builder().text("Che ore sono?").build(),
                AssistantMessage.builder().content("").toolCalls(List.of(CALL)).build(),
                ToolResponseMessage.builder().responses(List.of(RESPONSE)).build(),
                AssistantMessage.builder().content("Sono le 9:30.").build());
    }

    @Test
    void keepsToolCallsAndResultsAcrossReads() {
        repository.saveAll("conv-tools", turnWithTool());

        List<Message> stored = repository.findByConversationId("conv-tools");

        assertThat(stored).hasSize(4);
        assertThat(stored.get(0).getText()).isEqualTo("Che ore sono?");
        assertThat(((AssistantMessage) stored.get(1)).getToolCalls()).containsExactly(CALL);
        assertThat(((ToolResponseMessage) stored.get(2)).getResponses()).containsExactly(RESPONSE);
        assertThat(stored.get(3).getText()).isEqualTo("Sono le 9:30.");
        assertThat(repository.findConversationIds()).contains("conv-tools");
    }

    @Test
    void saveAllReplacesTheConversation() {
        repository.saveAll("conv-replace", turnWithTool());
        repository.saveAll("conv-replace", List.of(UserMessage.builder().text("Ciao").build()));

        assertThat(repository.findByConversationId("conv-replace"))
                .extracting(Message::getText)
                .containsExactly("Ciao");

        repository.deleteByConversationId("conv-replace");
        assertThat(repository.findByConversationId("conv-replace")).isEmpty();
    }

    @Test
    void chatMemoryIsBackedByPostgres() {
        chatMemory.add("conv-memory", turnWithTool());

        assertThat(repository.findByConversationId("conv-memory")).hasSize(4);
        assertThat(chatMemory.get("conv-memory")).hasSize(4);
    }
}
