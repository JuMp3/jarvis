package dev.jarvis.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jarvis.config.JarvisProperties;

@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    /** Sliding window over the conversation stored in PostgreSQL, so it survives restarts. */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, JarvisProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.agent().memoryMaxMessages())
                .build();
    }

    @Bean
    ApprovalService approvalService(ApprovalChannel channel, JarvisProperties properties) {
        return new ApprovalService(channel, properties.agent().approvalTimeout(), properties.tasks().approvalTimeout());
    }
}
