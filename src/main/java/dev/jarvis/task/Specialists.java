package dev.jarvis.task;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.tools.DocumentTools;
import dev.jarvis.agent.tools.SystemTools;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.Specialist;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.llm.ProviderRouter;
import dev.jarvis.mcp.McpTools;
import tools.jackson.databind.json.JsonMapper;

/**
 * The specialists defined under {@code jarvis.agents}, each with its own model, prompt, tools and limits.
 *
 * <p>Two kinds: {@code chat} (default) is one model call with read-only tools; {@code claude-code} drives
 * Claude Code on an allowed repository, after the user approves its plan.
 *
 * <p>A specialist starts from a clean context: it sees only the task, never the conversation, and gets no
 * task tools, so it cannot delegate in turn.
 */
@Component
public class Specialists {

    /** @param costUsd null when the specialist does not report it */
    public record Result(String text, Integer inputTokens, Integer outputTokens, Double costUsd) {
    }

    private final Map<String, SpecialistRunner> runners;
    private final Map<String, Duration> timeouts;

    public Specialists(JarvisProperties properties, ProviderRouter router, SystemTools systemTools,
                       DocumentTools documentTools, McpTools mcpTools, Environment environment,
                       JsonMapper json) {
        Map<String, Specialist> specs = properties.agents() == null ? Map.of() : properties.agents();
        Map<String, SpecialistRunner> built = new LinkedHashMap<>();
        Map<String, Duration> limits = new LinkedHashMap<>();
        specs.forEach((id, spec) -> {
            built.put(id, "claude-code".equals(spec.type())
                    ? new ClaudeCodeSpecialist(id, spec, claudeCodeEnvironment(environment), json)
                    : new ChatSpecialist(id, spec, router, systemTools, documentTools, mcpTools));
            if (spec.timeout() != null) {
                limits.put(id, spec.timeout());
            }
        });
        this.runners = Collections.unmodifiableMap(built);
        this.timeouts = Collections.unmodifiableMap(limits);
    }

    public boolean exists(String agent) {
        return agent != null && runners.containsKey(agent);
    }

    public Set<String> ids() {
        return runners.keySet();
    }

    /** @throws IllegalArgumentException if the specialist does not exist or the workspace is not allowed */
    void validate(String agent, String workspace) {
        if (!exists(agent)) {
            throw new IllegalArgumentException("Specialista sconosciuto: " + agent + ". Disponibili: " + ids());
        }
        runners.get(agent).validate(workspace);
    }

    public Specialists.Result run(AgentTask task, TaskContext context) {
        SpecialistRunner runner = runners.get(task.agent());
        if (runner == null) {
            throw new IllegalArgumentException("Unknown specialist " + task.agent());
        }
        return runner.run(task, context);
    }

    ProviderId provider(String agent) {
        return runners.get(agent).provider();
    }

    /** The specialist's own time limit, if it has one. */
    Optional<Duration> timeout(String agent) {
        return Optional.ofNullable(timeouts.get(agent));
    }

    /** Repositories the coding specialists may work on. */
    public List<Path> repositories() {
        return runners.values().stream()
                .filter(ClaudeCodeSpecialist.class::isInstance)
                .flatMap(runner -> ((ClaudeCodeSpecialist) runner).repositories().stream())
                .distinct()
                .toList();
    }

    /** Claude Code bills the same Anthropic key and workspace as Jarvis, never the login of the PC. */
    private static Map<String, String> claudeCodeEnvironment(Environment environment) {
        Map<String, String> variables = new LinkedHashMap<>();
        String apiKey = environment.getProperty("spring.ai.anthropic.api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            variables.put("ANTHROPIC_API_KEY", apiKey);
        }
        Map<String, String> headers = Binder.get(environment)
                .bind("spring.ai.anthropic.custom-headers", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        if (!headers.isEmpty()) {
            variables.put("ANTHROPIC_CUSTOM_HEADERS", headers.entrySet().stream()
                    .map(header -> header.getKey() + ": " + header.getValue())
                    .collect(Collectors.joining("\n")));
        }
        return variables;
    }
}
