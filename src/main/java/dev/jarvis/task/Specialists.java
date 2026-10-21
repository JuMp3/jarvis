package dev.jarvis.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicWebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.tools.SystemTools;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.Specialist;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.llm.ProviderRouter;

/**
 * The specialists defined under {@code jarvis.agents}, each with its own model, system prompt and tools.
 *
 * <p>A specialist starts from a clean context: it sees only the task, never the conversation. It gets no
 * task tools, so it cannot delegate in turn, and no tool that writes: the researcher reads untrusted web
 * content, and anything it suggests doing goes back through Jarvis and the user's approval.
 */
@Component
public class Specialists {

    /** Tool groups a specialist may use. None of them writes, sends or deletes. */
    static final Set<String> TOOL_GROUPS = Set.of("time", "web-search");
    private static final int DEFAULT_MAX_SEARCHES = 5;

    public record Result(String text, Integer inputTokens, Integer outputTokens) {
    }

    private record Runner(ProviderId provider, ChatClient client, Object[] tools) {
    }

    private final Map<String, Runner> runners;

    public Specialists(JarvisProperties properties, ProviderRouter router, SystemTools systemTools) {
        Map<String, Specialist> specs = properties.agents() == null ? Map.of() : properties.agents();
        Map<String, Runner> built = new LinkedHashMap<>();
        specs.forEach((id, spec) -> built.put(id, build(id, spec, router, systemTools)));
        this.runners = Collections.unmodifiableMap(built);
    }

    public boolean exists(String agent) {
        return agent != null && runners.containsKey(agent);
    }

    public Set<String> ids() {
        return runners.keySet();
    }

    ProviderId provider(String agent) {
        return runners.get(agent).provider();
    }

    /** Runs the task to the end on the calling thread (a virtual thread of the task executor). */
    public Result run(AgentTask task) {
        Runner runner = runners.get(task.agent());
        if (runner == null) {
            throw new IllegalArgumentException("Unknown specialist " + task.agent());
        }
        String prompt = "Compito:\n" + task.instructions();
        if (task.expectedOutput() != null && !task.expectedOutput().isBlank()) {
            prompt += "\n\nRisultato atteso:\n" + task.expectedOutput();
        }
        ChatClient.ChatClientRequestSpec request = runner.client().prompt().user(prompt);
        if (runner.tools().length > 0) {
            request.tools(runner.tools());
        }
        ChatResponse response = request.call().chatResponse();
        if (response == null) {
            throw new IllegalStateException("Empty response from specialist " + task.agent());
        }
        // With server-side web search the answer comes in several text blocks
        String text = response.getResults().stream()
                .map(generation -> generation.getOutput().getText())
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"))
                .strip();
        Usage usage = response.getMetadata().getUsage();
        return new Result(text, usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    private static Runner build(String id, Specialist spec, ProviderRouter router, SystemTools systemTools) {
        String property = "jarvis.agents." + id;
        List<String> tools = spec.tools() == null ? List.of() : spec.tools();
        List<String> unknown = tools.stream().filter(tool -> !TOOL_GROUPS.contains(tool)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(property + ".tools: unknown " + unknown + ", allowed " + TOOL_GROUPS);
        }
        ProviderId provider = ProviderId.parse(spec.provider())
                .orElseThrow(() -> new IllegalStateException(property + ".provider: unknown " + spec.provider()));
        ChatModel model = router.chatModel(provider).orElseThrow(() -> new IllegalStateException(
                property + ".provider: " + provider.id() + " is not in jarvis.providers.enabled"));

        ChatOptions.Builder<?> options;
        if (tools.contains("web-search")) {
            if (provider != ProviderId.ANTHROPIC) {
                throw new IllegalStateException(property + ": web-search needs provider anthropic");
            }
            options = AnthropicChatOptions.builder().webSearchTool(AnthropicWebSearchTool.builder()
                    .maxUses(spec.maxSearches() == null ? DEFAULT_MAX_SEARCHES : spec.maxSearches())
                    .build());
        } else {
            options = ChatOptions.builder();
        }
        if (spec.model() != null && !spec.model().isBlank()) {
            options.model(spec.model());
        }
        if (spec.maxTokens() != null) {
            options.maxTokens(spec.maxTokens());
        }
        ChatClient client = ChatClient.builder(model)
                .defaultSystem(spec.systemPrompt())
                .defaultOptions(options)
                .build();
        return new Runner(provider, client, tools.contains("time") ? new Object[] {systemTools} : new Object[0]);
    }
}
