package dev.jarvis.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jarvis.config.JarvisProperties;

@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    /** In-memory window for now; Phase 1 swaps in a JDBC repository to survive restarts. */
    @Bean
    ChatMemory chatMemory(JarvisProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(properties.agent().memoryMaxMessages())
                .build();
    }

    @Bean
    ApprovalService approvalService(ApprovalChannel channel, JarvisProperties properties) {
        return new ApprovalService(channel, properties.agent().approvalTimeout());
    }
}
