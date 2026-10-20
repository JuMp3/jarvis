package dev.jarvis.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 256 * 1024;

    private final JarvisWebSocketHandler handler;

    public WebSocketConfig(JarvisWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // No setAllowedOrigins: only same-origin pages may connect, so other websites
        // open in the browser cannot drive the local assistant. Add the Tauri origin in Phase 4.
        registry.addHandler(handler, "/ws");
    }

    /** Default container buffers are a few KB; recorded speech needs much more. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_AUDIO_BYTES);
        container.setMaxTextMessageBufferSize(MAX_TEXT_BYTES);
        return container;
    }
}
