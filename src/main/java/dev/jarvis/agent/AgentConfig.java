package dev.jarvis.agent;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jarvis.config.JarvisProperties;

@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    /**
     * Sliding window over the conversation stored in PostgreSQL, so it survives restarts. Every message added
     * also goes to the archive: the window drops the old ones and clearing it drops all of them.
     */
    @Bean
    ChatMemory chatMemory(PostgresChatMemoryRepository repository, JarvisProperties properties) {
        ChatMemory window = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.agent().memoryMaxMessages())
                .build();
        return new ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                repository.archive(conversationId, messages);
                window.add(conversationId, messages);
            }

            @Override
            public List<Message> get(String conversationId) {
                return window.get(conversationId);
            }

            @Override
            public void clear(String conversationId) {
                window.clear(conversationId);
            }
        };
    }

    @Bean
    ApprovalService approvalService(ApprovalChannel channel, JarvisProperties properties) {
        return new ApprovalService(channel, properties.agent().approvalTimeout(), properties.tasks().approvalTimeout());
    }
}
