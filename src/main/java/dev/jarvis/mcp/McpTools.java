package dev.jarvis.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.McpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;

/**
 * Tools of the MCP servers listed in {@code jarvis.mcp.servers}, filtered and split by what they do.
 *
 * <p>Only the tools named in the configuration are exposed, as {@code <server>_<tool>}. Read tools go to the
 * specialists that list {@code mcp:<server>} (they read untrusted content and cannot act); write tools go
 * only to Jarvis, and each call waits for the user's approval of its exact arguments. Servers connect in
 * the background: Jarvis starts without waiting, and one that cannot be reached is skipped with a warning.
 */
@Component
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    private static final int RETRIES = 8;
    private static final long RETRY_SECONDS = 15;

    // Filled once a server connects: read at use time, so new tools appear live
    private final Map<String, List<ToolCallback>> readTools = new ConcurrentHashMap<>();
    private final List<ToolCallback> writeTools = new CopyOnWriteArrayList<>();
    private final Map<String, McpSyncClient> connected = new ConcurrentHashMap<>();
    // Built here, so not closed by Spring AI
    private final List<McpSyncClient> stdioClients = new CopyOnWriteArrayList<>();
    private final Set<String> configured;
    private final ObjectProvider<McpStdioClientProperties> stdio;
    private final ObjectProvider<McpClientCommonProperties> common;
    private volatile boolean closed;

    public McpTools(JarvisProperties properties, ObjectProvider<List<McpSyncClient>> clients,
                    ApprovalService approvals, ObjectProvider<McpStdioClientProperties> stdio,
                    ObjectProvider<McpClientCommonProperties> common) {
        this.stdio = stdio;
        this.common = common;
        Map<String, McpServer> servers = properties.mcp() == null || properties.mcp().servers() == null
                ? Map.of() : properties.mcp().servers();
        this.configured = Collections.unmodifiableSet(servers.keySet());
        List<McpSyncClient> available = clients.getIfAvailable(List::of);
        servers.forEach((name, server) -> {
            McpSyncClient client = stdioParameters(name) != null ? stdioClient(name) : available.stream()
                    .filter(candidate -> name.equals(candidate.getClientInfo().title()))
                    .findFirst().orElse(null);
            if (client == null) {
                log.warn("jarvis.mcp.servers.{} has no connection under spring.ai.mcp.client: skipped", name);
            } else {
                connectInBackground(name, server, client, approvals);
            }
        });
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

    /**
     * Jarvis does not wait for the servers: their tools appear once they are ready. A server that does not
     * answer (an npx package still downloading, no network) is tried again for a few minutes.
     */
    private void connectInBackground(String name, McpServer server, McpSyncClient first, ApprovalService approvals) {
        Thread.ofVirtual().name("mcp-connect-" + name).start(() -> {
            McpSyncClient client = first;
            for (int attempt = 0; attempt <= RETRIES; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(RETRY_SECONDS * 1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (closed) {
                        return; // application closing or restarting
                    }
                    client = retryClient(name, client);
                }
                if (connect(name, server, client, approvals)) {
                    return;
                }
            }
            log.warn("MCP server {} still unavailable after {} retries: restart Jarvis to try again", name, RETRIES);
        });
    }

    /**
     * A stdio transport serves a single connection: each attempt needs a new client, and closing the failed
     * one stops the processes it started. Other transports try again on the same client.
     */
    private McpSyncClient retryClient(String name, McpSyncClient failed) {
        if (stdioParameters(name) == null) {
            return failed;
        }
        failed.closeGracefully();
        stdioClients.remove(failed);
        return stdioClient(name);
    }

    /**
     * Stdio servers get a client of our own, to close their whole process tree. Spring AI builds one too, but
     * never initializes it, so it starts no process.
     */
    private McpSyncClient stdioClient(String name) {
        McpClientCommonProperties properties = common.getIfAvailable(McpClientCommonProperties::new);
        McpSyncClient client = McpClient.sync(new TreeClosingStdioTransport(stdioParameters(name)))
                .clientInfo(McpSchema.Implementation.builder(properties.getName() + " - " + name, properties.getVersion())
                        .title(name).build())
                .requestTimeout(properties.getRequestTimeout())
                // the first start of an npx server can take longer than the SDK's 20 seconds; nobody waits for it
                .initializationTimeout(properties.getRequestTimeout())
                .build();
        stdioClients.add(client);
        return client;
    }

    private ServerParameters stdioParameters(String name) {
        McpStdioClientProperties properties = stdio.getIfAvailable();
        return properties == null ? null : properties.toServerParameters().get(name);
    }

    @PreDestroy
    void closeStdioClients() {
        closed = true;
        stdioClients.forEach(McpSyncClient::closeGracefully);
    }

    /** @return false if the server could not be reached and is worth trying again */
    private boolean connect(String name, McpServer server, McpSyncClient client, ApprovalService approvals) {
        List<McpSchema.Tool> tools;
        try {
            if (!client.isInitialized()) {
                client.initialize();
            }
            tools = listAll(client);
        } catch (RuntimeException e) {
            log.warn("MCP server {} not available yet, retrying in background: {}", name, e.getMessage());
            return false;
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
        connected.put(name, client);
        List<String> offered = tools.stream().map(McpSchema.Tool::name).toList();
        List<String> missing = new ArrayList<>(reads);
        missing.addAll(writes);
        missing.removeAll(offered);
        if (!missing.isEmpty()) {
            log.warn("MCP server {} does not offer the configured tools {}; it offers {}", name, missing, offered);
        }
        log.info("MCP server {}: {} read tools, {} write tools", name, serverReads.size(),
                tools.stream().filter(tool -> writes.contains(tool.name())).count());
        return true;
    }

    /**
     * Calls a tool that is never exposed to the model, for the Settings section (e.g. the sign-in of an account).
     *
     * @return the text the server answered, or why the call was not possible
     */
    public String adminCall(String server, String tool) {
        McpSyncClient client = connected.get(server);
        if (client == null) {
            return "Il collegamento " + server + " non è attivo: salva le impostazioni e attendi il riavvio.";
        }
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, Map.of()));
            return result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return "Il server " + server + " non ha risposto: " + e.getMessage();
        }
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
