package dev.jarvis.mcp;

import java.lang.reflect.Field;
import java.util.List;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stdio transport that, when closed, also stops the processes its server started.
 *
 * <p>The SDK destroys only the process it launched. On Windows that is {@code cmd.exe} running {@code npx}:
 * npx and the server survive, and since npx keeps the server's stdin open, neither ever exits. Each restart
 * of Jarvis, or failed attempt to connect, would leave two more Node processes behind.
 */
class TreeClosingStdioTransport extends StdioClientTransport {

    TreeClosingStdioTransport(ServerParameters parameters) {
        super(parameters, new JacksonMcpJsonMapper(JsonMapper.shared()));
    }

    @Override
    public Mono<Void> closeGracefully() {
        // Collected before the SDK destroys the root: afterwards its children are no longer linked to it
        List<ProcessHandle> descendants = process() == null ? List.of() : process().descendants().toList();
        return super.closeGracefully().doFinally(signal -> descendants.forEach(ProcessHandle::destroyForcibly));
    }

    // shortcut: the SDK (mcp-core 2.0.0) keeps the process private; without the field only the root is closed
    private Process process() {
        try {
            Field field = StdioClientTransport.class.getDeclaredField("process");
            field.setAccessible(true);
            return (Process) field.get(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
