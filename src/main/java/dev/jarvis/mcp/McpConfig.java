package dev.jarvis.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.McpServer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

@Configuration(proxyBeanMethods = false)
public class McpConfig {

    /**
     * Adds the headers of {@code jarvis.mcp.servers.<name>.headers} (e.g. a token) to the requests of that
     * server only, matched by its connection URL: Spring AI's connection properties have no headers.
     */
    @Bean
    McpSyncHttpClientRequestCustomizer mcpServerHeaders(JarvisProperties properties,
                                                        ObjectProvider<McpStreamableHttpClientProperties> http) {
        Map<String, Map<String, String>> headersByUrl = new LinkedHashMap<>();
        McpStreamableHttpClientProperties connections = http.getIfAvailable();
        Map<String, McpServer> servers = properties.mcp() == null || properties.mcp().servers() == null
                ? Map.of() : properties.mcp().servers();
        if (connections != null) {
            servers.forEach((name, server) -> {
                var connection = connections.getConnections().get(name);
                if (connection != null && connection.url() != null && server.headers() != null) {
                    headersByUrl.put(connection.url(), server.headers());
                }
            });
        }
        return (request, method, endpoint, body, context) -> headersByUrl.forEach((url, headers) -> {
            if (endpoint.toString().startsWith(url)) {
                headers.forEach((key, value) -> {
                    if (value != null && !value.isBlank()) {
                        request.setHeader(key, value);
                    }
                });
            }
        });
    }
}
