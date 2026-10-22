package dev.jarvis.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.client.transport.ServerParameters;

/**
 * Runs an MCP server set up as {@code npx -y <package>@<version> ...} with the Node of the desktop app instead,
 * where that package is already installed (desktop/prepare-node.ps1). The settings keep the npx form, so development
 * still uses npx and no install folder ends up in the database. Pure logic.
 *
 * <p>{@code --use-system-ca}: Node trusts the certificates of the system, where IT also installs corporate CAs (TLS
 * inspection), as the JVM of the desktop app does.
 */
final class NpxCommand {

    private static final Pattern PACKAGE = Pattern.compile("^(@?[^@]+)@([^@]+)$");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BIN = Pattern.compile("\"bin\"\\s*:\\s*(\"([^\"]+)\"|\\{[^}]*?\"[^\"]+\"\\s*:\\s*\"([^\"]+)\")");

    private NpxCommand() {
    }

    /** @param nodeHome folder with node(.exe) and node_modules; null or blank: npx as configured */
    static ServerParameters resolve(ServerParameters parameters, String nodeHome) {
        if (parameters == null || nodeHome == null || nodeHome.isBlank()) {
            return parameters;
        }
        String command = Path.of(parameters.getCommand()).getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> args = parameters.getArgs();
        if (!command.equals("npx") && !command.equals("npx.cmd")) {
            return parameters;
        }
        int at = args.indexOf("-y") + 1; // the package follows -y; without it, the first argument
        if (at >= args.size()) {
            return parameters;
        }
        Matcher spec = PACKAGE.matcher(args.get(at));
        if (!spec.matches()) {
            return parameters; // no pinned version: let npx pick it
        }
        Path home = Path.of(nodeHome);
        Path node = home.resolve(command.endsWith(".cmd") ? "node.exe" : "node");
        Path module = home.resolve("node_modules").resolve(spec.group(1));
        String manifest;
        try {
            manifest = Files.readString(module.resolve("package.json"));
        } catch (IOException e) {
            return parameters; // not bundled
        }
        Matcher version = VERSION.matcher(manifest);
        Matcher bin = BIN.matcher(manifest);
        if (!Files.isRegularFile(node) || !version.find() || !version.group(1).equals(spec.group(2)) || !bin.find()) {
            return parameters; // another version bundled: the configured one wins
        }
        String script = bin.group(2) != null ? bin.group(2) : bin.group(3);
        List<String> resolved = new ArrayList<>(List.of("--use-system-ca", module.resolve(script).toString()));
        resolved.addAll(args.subList(at + 1, args.size()));
        return ServerParameters.builder(node.toString()).args(resolved).env(parameters.getEnv()).build();
    }
}
