package dev.jarvis.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.modelcontextprotocol.client.transport.ServerParameters;

class TreeClosingStdioTransportTest {

    /** Like npx under cmd.exe: the SDK alone would destroy cmd.exe and leave ping running. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void closingStopsTheProcessesTheServerStarted() {
        var transport = new TreeClosingStdioTransport(ServerParameters.builder("cmd.exe")
                .args("/c", "ping", "-n", "60", "127.0.0.1").build());
        transport.connect(message -> message).block();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !pings().isEmpty());
        List<ProcessHandle> started = pings();
        assertThat(started).isNotEmpty();

        transport.closeGracefully().block(Duration.ofSeconds(10));

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> started.stream().noneMatch(ProcessHandle::isAlive));
    }

    private static List<ProcessHandle> pings() {
        return ProcessHandle.current().descendants()
                .filter(process -> process.info().command().orElse("").toLowerCase().endsWith("ping.exe"))
                .toList();
    }
}
