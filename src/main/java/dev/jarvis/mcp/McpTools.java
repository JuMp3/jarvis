package dev.jarvis.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.McpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tools of the MCP servers listed in {@code jarvis.mcp.servers}, filtered and split by what they do.
 *
 * <p>Only the tools named in the configuration are exposed, as {@code <server>_<tool>}. Read tools go to the
 * specialists that list {@code mcp:<server>} (they read untrusted content and cannot act); write tools go
 * only to Jarvis, and each call waits for the user's approval of its exact arguments. A server that cannot
 * be reached at startup is skipped with a warning: Jarvis starts anyway.
 */
@Component
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    private final Map<String, List<ToolCallback>> readTools = new LinkedHashMap<>();
    private final List<ToolCallback> writeTools = new ArrayList<>();
    private final Set<String> configured;

    public McpTools(JarvisProperties properties, ObjectProvider<List<McpSyncClient>> clients,
                    ApprovalService approvals) {
        Map<String, McpServer> servers = properties.mcp() == null || properties.mcp().servers() == null
                ? Map.of() : properties.mcp().servers();
        this.configured = Collections.unmodifiableSet(servers.keySet());
        List<McpSyncClient> available = clients.getIfAvailable(List::of);
        servers.forEach((name, server) -> connect(name, server, available, approvals));
    }

    /** Whether {@code jarvis.mcp.servers} defines the server (it may still be unreachable). */
    public boolean configured(String server) {
        return configured.contains(server);
    }

    /** Read-only tools of a server; empty if it is unreachable. */
    public List<ToolCallback> readTools(String server) {
        return readTools.getOrDefault(server, List.of());
    }

    /** Tools that change something, for Jarvis only, each behind an approval. */
    public List<ToolCallback> writeTools() {
        return writeTools;
    }

    private void connect(String name, McpServer server, List<McpSyncClient> clients, ApprovalService approvals) {
        McpSyncClient client = clients.stream()
                .filter(candidate -> name.equals(candidate.getClientInfo().title()))
                .findFirst().orElse(null);
        if (client == null) {
            log.warn("jarvis.mcp.servers.{} has no connection under spring.ai.mcp.client: skipped", name);
            return;
        }
        List<McpSchema.Tool> tools;
        try {
            if (!client.isInitialized()) {
                client.initialize();
            }
            tools = listAll(client);
        } catch (RuntimeException e) {
            log.warn("MCP server {} unavailable, its tools are disabled: {}", name, e.getMessage());
            return;
        }
        List<String> reads = server.readTools() == null ? List.of() : server.readTools();
        List<String> writes = server.writeTools() == null ? List.of() : server.writeTools();
        List<ToolCallback> serverReads = new ArrayList<>();
        for (McpSchema.Tool tool : tools) {
            if (reads.contains(tool.name())) {
                serverReads.add(renamed(name, tool.name(), new SyncMcpToolCallback(client, tool)));
            } else if (writes.contains(tool.name())) {
                writeTools.add(new ApprovalToolCallback(renamed(name, tool.name(), new SyncMcpToolCallback(client, tool)),
                        server.label() == null ? name : server.label(), approvals));
            }
        }
        readTools.put(name, List.copyOf(serverReads));
        List<String> offered = tools.stream().map(McpSchema.Tool::name).toList();
        List<String> missing = new ArrayList<>(reads);
        missing.addAll(writes);
        missing.removeAll(offered);
        if (!missing.isEmpty()) {
            log.warn("MCP server {} does not offer the configured tools {}; it offers {}", name, missing, offered);
        }
        log.info("MCP server {}: {} read tools, {} write tools", name, serverReads.size(),
                tools.stream().filter(tool -> writes.contains(tool.name())).count());
    }

    private static List<McpSchema.Tool> listAll(McpSyncClient client) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
            tools.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        return tools;
    }

    /**
     * "github_create_issue": no clash between servers, and the owner is clear to the model and in approvals.
     * Built from the MCP tool name: Spring AI's definition already carries a prefix of its own.
     */
    private static ToolCallback renamed(String server, String toolName, ToolCallback delegate) {
        ToolDefinition original = delegate.getToolDefinition();
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(server + "_" + toolName)
                .description(original.description())
                .inputSchema(original.inputSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return delegate.call(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return delegate.call(toolInput, toolContext);
            }
        };
    }
}
