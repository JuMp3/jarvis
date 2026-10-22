package dev.jarvis.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.client.transport.ServerParameters;

class NpxCommandTest {

    @TempDir
    Path home;

    private static ServerParameters npx(String spec) {
        return ServerParameters.builder("npx.cmd").args("-y", spec, "--preset", "outlook")
                .env(Map.of("MS365_MCP_TENANT_ID", "consumers")).build();
    }

    private void bundle(String version) throws Exception {
        Path module = Files.createDirectories(home.resolve("node_modules/@softeria/ms-365-mcp-server"));
        Files.writeString(module.resolve("package.json"), """
                {"name": "@softeria/ms-365-mcp-server", "version": "%s",
                 "bin": {"ms-365-mcp-server": "dist/index.js"}}""".formatted(version));
        Files.writeString(home.resolve("node.exe"), "");
    }

    @Test
    void theBundledPackageRunsWithTheBundledNodeAndTheSystemCertificates() throws Exception {
        bundle("0.170.2");

        ServerParameters resolved = NpxCommand.resolve(npx("@softeria/ms-365-mcp-server@0.170.2"), home.toString());

        assertThat(resolved.getCommand()).isEqualTo(home.resolve("node.exe").toString());
        assertThat(resolved.getArgs()).containsExactly("--use-system-ca",
                home.resolve("node_modules/@softeria/ms-365-mcp-server/dist/index.js").toString(), "--preset", "outlook");
        assertThat(resolved.getEnv()).containsEntry("MS365_MCP_TENANT_ID", "consumers");
    }

    @Test
    void otherwiseNpxAsConfigured() throws Exception {
        ServerParameters configured = npx("@softeria/ms-365-mcp-server@0.170.2");
        assertThat(NpxCommand.resolve(configured, null)).isSameAs(configured); // development: no bundle
        assertThat(NpxCommand.resolve(configured, home.toString())).isSameAs(configured); // not bundled

        bundle("0.160.0");
        assertThat(NpxCommand.resolve(configured, home.toString())).isSameAs(configured); // another version

        ServerParameters unpinned = npx("@softeria/ms-365-mcp-server");
        assertThat(NpxCommand.resolve(unpinned, home.toString())).isSameAs(unpinned);
        ServerParameters other = ServerParameters.builder("uvx").args(List.of("some-server")).build();
        assertThat(NpxCommand.resolve(other, home.toString())).isSameAs(other);
    }
}
