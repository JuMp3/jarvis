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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.tools.DocumentTools;
import dev.jarvis.agent.tools.MailTools;
import dev.jarvis.agent.tools.NoteTools;
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

    private static final Logger log = LoggerFactory.getLogger(Specialists.class);

    private final Map<String, SpecialistRunner> runners;
    private final Map<String, Duration> timeouts;
    private final String language;
    /** The specialists that run the routines, and the reviewer: Jarvis does not delegate to them. */
    private final Set<String> routines = new java.util.HashSet<>();
    private final Set<String> reviewers = new java.util.HashSet<>();
    private final Set<String> reviewed = new java.util.HashSet<>();
    private final Map<String, List<String>> delegates = new java.util.HashMap<>();

    public Specialists(JarvisProperties properties, ProviderRouter router, SystemTools systemTools,
                       DocumentTools documentTools, McpTools mcpTools, MailTools mailTools, NoteTools noteTools,
                       Environment environment, JsonMapper json) {
        Map<String, Specialist> specs = properties.agents() == null ? Map.of() : properties.agents();
        Map<String, SpecialistRunner> built = new LinkedHashMap<>();
        Map<String, Duration> limits = new LinkedHashMap<>();
        specs.forEach((id, spec) -> {
            // Repository folders come from Settings: before the wizard there are none
            if ("claude-code".equals(spec.type()) && new WorkspacePolicy(spec.workspaces()).roots().isEmpty()) {
                log.warn("Specialist {} is off: no repository folder yet (Settings → Cartelle)", id);
                return;
            }
            built.put(id, switch (spec.type() == null ? "chat" : spec.type()) {
                case "claude-code" -> new ClaudeCodeSpecialist(id, spec, claudeCodeEnvironment(environment), json,
                        properties.language());
                case "a2a" -> new A2aSpecialist(id, spec, properties.language());
                default -> new ChatSpecialist(id, spec, router, systemTools, documentTools, mcpTools, mailTools,
                        noteTools, properties.language(), delegationPrompt(spec, specs));
            });
            if (Boolean.TRUE.equals(spec.routine())) {
                routines.add(id);
            }
            if (Boolean.TRUE.equals(spec.reviewer())) {
                reviewers.add(id);
            }
            if (Boolean.TRUE.equals(spec.review())) {
                reviewed.add(id);
            }
            if (spec.delegates() != null && !spec.delegates().isEmpty()) {
                delegates.put(id, List.copyOf(spec.delegates()));
            }
            if (spec.timeout() != null) {
                limits.put(id, spec.timeout());
            }
        });
        this.language = properties.language();
        this.runners = Collections.unmodifiableMap(built);
        this.timeouts = Collections.unmodifiableMap(limits);
    }

    public boolean exists(String agent) {
        return agent != null && runners.containsKey(agent);
    }

    /** The specialists Jarvis may delegate to. */
    public Set<String> ids() {
        Set<String> ids = new java.util.LinkedHashSet<>(runners.keySet());
        ids.removeAll(routines);
        ids.removeAll(reviewers);
        return ids;
    }

    /**
     * A specialist asking another one for part of its work: only those in its delegates, read-only chat ones.
     *
     * @throws IllegalArgumentException with a message for the model
     */
    void validateDelegation(String from, String to) {
        if (!delegates.getOrDefault(from, List.of()).contains(to) || !(runners.get(to) instanceof ChatSpecialist)
                || routines.contains(to) || reviewers.contains(to)) {
            throw new IllegalArgumentException("Non puoi delegare a " + to + ". Puoi chiedere a: "
                    + delegates.getOrDefault(from, List.of()));
        }
    }

    /** What a specialist that may delegate reads about the ones it may ask. */
    private static String delegationPrompt(Specialist spec, Map<String, Specialist> all) {
        if (spec.delegates() == null || spec.delegates().isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder("\n\nCon askSpecialist puoi chiedere una parte del lavoro a:\n");
        for (String id : spec.delegates()) {
            Specialist other = all.get(id);
            if (other == null || !"chat".equals(other.type() == null ? "chat" : other.type())) {
                throw new IllegalStateException("delegates: " + id + " is not a chat specialist");
            }
            prompt.append("- ").append(id).append(": ").append(other.description().strip()).append('\n');
        }
        return prompt.toString();
    }

    /** The specialist that runs the routines, if configured and on. */
    public Optional<String> routineRunner() {
        return routines.stream().filter(runners::containsKey).findFirst();
    }

    /**
     * @param routine the task is a routine: only the routine specialist may run it, and only it
     * @throws IllegalArgumentException if the specialist does not exist or the workspace is not allowed
     */
    void validate(String agent, String workspace, boolean routine) {
        if (!exists(agent) || reviewers.contains(agent) || routines.contains(agent) != routine) {
            throw new IllegalArgumentException("Specialista sconosciuto: " + agent + ". Disponibili: " + ids());
        }
        runners.get(agent).validate(workspace);
    }

    public Specialists.Result run(AgentTask task, TaskContext context) {
        SpecialistRunner runner = runners.get(task.agent());
        if (runner == null) {
            throw new IllegalArgumentException("Unknown specialist " + task.agent());
        }
        String reviewer = reviewers.stream().findFirst().orElse(null);
        boolean checked = reviewer != null && reviewed.contains(task.agent());
        if (checked) {
            context.step(1, 2); // work, then review: a third step only if it is redone
        }
        Result result = runner.run(task, context);
        if (!checked || result.text() == null || result.text().isBlank()) {
            return result;
        }
        context.step(2, 2);
        context.progress(Review.CHECKING.in(language));
        Result verdict = runners.get(reviewer).run(Review.task(task, result.text()), context);
        Review review = Review.of(verdict.text());
        if (review.ok()) {
            return Review.sum(result, verdict, result.text());
        }
        log.info("Reviewer asked to redo task {} ({})", task.id(), task.agent());
        context.step(3, 3);
        context.progress(Review.REDOING.in(language));
        Result redone = runner.run(Review.redo(task, result.text(), review.notes()), context);
        return Review.sum(Review.sum(result, verdict, null), redone, redone.text());
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
