package dev.jarvis.agent;

import java.util.Map;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import dev.jarvis.agent.tools.NoteTools;
import dev.jarvis.agent.tools.SystemTools;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.llm.ProviderRouter;
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

    private final ProviderRouter router;
    private final Object[] tools;

    public AgentService(ProviderRouter router, SystemTools systemTools, NoteTools noteTools) {
        this.router = router;
        this.tools = new Object[] {systemTools, noteTools};
    }

    public Flux<String> stream(ProviderId provider, String conversationId, String sessionId, String userText) {
        return router.client(provider)
                .prompt()
                .user(userText)
                .tools(tools)
                .toolContext(Map.of(SESSION_ID_KEY, sessionId))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
