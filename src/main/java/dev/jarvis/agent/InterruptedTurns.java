package dev.jarvis.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Closes a conversation whose last turn was interrupted. Without it the model answers the interrupted
 * question again in the next turn, and a tool call left without its result makes the provider reject
 * every following request. Pure logic, no Spring dependency.
 */
public final class InterruptedTurns {

    static final String NOTE = "[risposta interrotta dall'utente]";
    static final String TOOL_RESULT = "Interrotto dall'utente prima del completamento.";

    private InterruptedTurns() {
    }

    /**
     * @param history       the conversation as stored in memory
     * @param partialAnswer text already streamed to the user in the interrupted turn
     * @return messages to append; empty if the last turn ended normally
     */
    public static List<Message> closingMessages(List<Message> history, String partialAnswer) {
        if (history.isEmpty()) {
            return List.of();
        }
        List<Message> closing = new ArrayList<>();
        if (history.getLast() instanceof AssistantMessage assistant) {
            if (!assistant.hasToolCalls()) {
                return List.of();
            }
            closing.add(ToolResponseMessage.builder()
                    .responses(assistant.getToolCalls().stream()
                            .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), TOOL_RESULT))
                            .toList())
                    .build());
        }
        String text = partialAnswer == null || partialAnswer.isBlank() ? NOTE : partialAnswer.strip() + " " + NOTE;
        closing.add(AssistantMessage.builder().content(text).build());
        return closing;
    }
}
