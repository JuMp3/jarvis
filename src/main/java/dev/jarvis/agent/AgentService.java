package dev.jarvis.agent;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import dev.jarvis.agent.tools.DesktopTools;
import dev.jarvis.agent.tools.NoteTools;
import dev.jarvis.agent.tools.SystemTools;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.llm.ProviderRouter;
import dev.jarvis.mcp.McpTools;
import dev.jarvis.task.TaskTools;
import reactor.core.publisher.Flux;

/**
 * One conversation turn: user text in, streamed answer out.
 *
 * <p>Spring AI runs the agent loop (model asks for a tool, the tool runs, the result goes back to the
 * model) until the model answers in plain text. Tools receive the WebSocket session id through the
 * tool context so they can ask the right user for approval.
 */
@Service
public class AgentService {

    public static final String SESSION_ID_KEY = "jarvis.sessionId";
    public static final String CONVERSATION_ID_KEY = "jarvis.conversationId";

    private final ProviderRouter router;
    private final ChatMemory memory;
    private final Object[] tools;
    /** MCP tools that change something, each behind an approval; reading is left to specialists. */
    private final List<ToolCallback> mcpWriteTools;

    public AgentService(ProviderRouter router, ChatMemory memory, SystemTools systemTools, NoteTools noteTools,
                        DesktopTools desktopTools, TaskTools taskTools, McpTools mcpTools) {
        this.router = router;
        this.memory = memory;
        this.tools = new Object[] {systemTools, noteTools, desktopTools, taskTools};
        this.mcpWriteTools = mcpTools.writeTools();
    }

    /** @param spoken the answer will be read aloud: use the provider's voice model, if configured */
    public Flux<String> stream(ProviderId provider, String conversationId, String sessionId, String userText,
                               boolean spoken) {
        // The previous turn may have died with the process (crash, kill) without being closed
        closeInterruptedTurn(conversationId, "");
        ChatClient.ChatClientRequestSpec request = router.client(provider).prompt();
        if (spoken) {
            router.voiceModel(provider).ifPresent(model -> request.options(ChatOptions.builder().model(model)));
        }
        if (!mcpWriteTools.isEmpty()) {
            request.toolCallbacks(mcpWriteTools);
        }
        return request
                .user(userText)
                .tools(tools)
                .toolContext(Map.of(SESSION_ID_KEY, sessionId, CONVERSATION_ID_KEY, conversationId))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    /** Records that the last turn was interrupted; does nothing if it ended normally. */
    public void closeInterruptedTurn(String conversationId, String partialAnswer) {
        List<Message> closing = InterruptedTurns.closingMessages(memory.get(conversationId), partialAnswer);
        if (!closing.isEmpty()) {
            memory.add(conversationId, closing);
        }
    }
}
