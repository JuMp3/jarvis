package dev.jarvis.task;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.ai.tool.ToolCallback;

import dev.jarvis.agent.tools.DocumentTools;
import dev.jarvis.agent.tools.SystemTools;
import dev.jarvis.config.JarvisProperties.Specialist;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.llm.ProviderRouter;
import dev.jarvis.mcp.McpTools;

/** A specialist that is one model call with its own system prompt and read-only tools (researcher, writer). */
final class ChatSpecialist implements SpecialistRunner {

    /** Tool groups a chat specialist may use. None of them writes, sends or deletes. */
    static final Set<String> TOOL_GROUPS = Set.of("time", "web-search", "documents");
    private static final int DEFAULT_MAX_SEARCHES = 5;
    private static final String MCP = "mcp:";

    private final ProviderId provider;
    private final ChatClient client;
    private final Object[] tools;
    private final List<ToolCallback> mcpCallbacks;

    ChatSpecialist(String id, Specialist spec, ProviderRouter router, SystemTools systemTools,
                   DocumentTools documentTools, McpTools mcpTools) {
        String property = "jarvis.agents." + id;
        List<String> toolGroups = spec.tools() == null ? List.of() : spec.tools();
        List<String> unknown = toolGroups.stream()
                .filter(tool -> !TOOL_GROUPS.contains(tool))
                .filter(tool -> !(tool.startsWith(MCP) && mcpTools.configured(tool.substring(MCP.length()))))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(property + ".tools: unknown " + unknown + ", allowed " + TOOL_GROUPS
                    + " and mcp:<server> for the servers in jarvis.mcp.servers");
        }
        this.provider = ProviderId.parse(spec.provider())
                .orElseThrow(() -> new IllegalStateException(property + ".provider: unknown " + spec.provider()));
        ChatModel model = router.chatModel(provider).orElseThrow(() -> new IllegalStateException(
                property + ".provider: " + provider.id() + " is not in jarvis.providers.enabled"));

        ChatOptions.Builder<?> options;
        if (toolGroups.contains("web-search")) {
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
        this.client = ChatClient.builder(model)
                .defaultSystem(spec.systemPrompt())
                .defaultOptions(options)
                .build();
        List<Object> granted = new ArrayList<>();
        if (toolGroups.contains("time")) {
            granted.add(systemTools);
        }
        if (toolGroups.contains("documents")) {
            granted.add(documentTools);
        }
        this.tools = granted.toArray();
        // Read-only tools of MCP servers, resolved at startup (empty if the server is unreachable)
        this.mcpCallbacks = toolGroups.stream()
                .filter(tool -> tool.startsWith(MCP))
                .flatMap(tool -> mcpTools.readTools(tool.substring(MCP.length())).stream())
                .toList();
    }

    @Override
    public Specialists.Result run(AgentTask task, TaskContext context) {
        String prompt = "Compito:\n" + task.instructions();
        if (task.expectedOutput() != null && !task.expectedOutput().isBlank()) {
            prompt += "\n\nRisultato atteso:\n" + task.expectedOutput();
        }
        ChatClient.ChatClientRequestSpec request = client.prompt().user(prompt);
        if (tools.length > 0) {
            request.tools(tools);
        }
        if (!mcpCallbacks.isEmpty()) {
            request.toolCallbacks(mcpCallbacks);
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
        return new Specialists.Result(text, usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(), null);
    }

    @Override
    public ProviderId provider() {
        return provider;
    }
}
