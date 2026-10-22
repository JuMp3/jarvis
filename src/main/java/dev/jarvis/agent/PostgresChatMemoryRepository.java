package dev.jarvis.agent;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversation memory on PostgreSQL (table {@code chat_message}, see schema.sql).
 *
 * <p>Unlike Spring AI's JdbcChatMemoryRepository it keeps tool calls and tool results: without them
 * the model forgets, in the next turn, which tools it used and what they returned.
 *
 * <p>The window of {@code chat_message} is rewritten at each turn; {@link #archive} appends to {@code chat_archive},
 * which is never trimmed nor cleared.
 */
@Repository
public class PostgresChatMemoryRepository implements ChatMemoryRepository {

    private static final TypeReference<List<AssistantMessage.ToolCall>> TOOL_CALLS = new TypeReference<>() {
    };
    private static final TypeReference<List<ToolResponseMessage.ToolResponse>> TOOL_RESPONSES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public PostgresChatMemoryRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbc.sql("SELECT DISTINCT conversation_id FROM chat_message").query(String.class).list();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbc.sql("""
                        SELECT type, content, tool_calls, tool_responses FROM chat_message
                        WHERE conversation_id = ? ORDER BY seq""")
                .param(conversationId)
                .query((rs, row) -> toMessage(MessageType.valueOf(rs.getString("type")), rs.getString("content"),
                        rs.getString("tool_calls"), rs.getString("tool_responses")))
                .list();
    }

    /** Replaces the whole conversation, as the {@link ChatMemoryRepository} contract requires. */
    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        deleteByConversationId(conversationId);
        for (int seq = 0; seq < messages.size(); seq++) {
            Message message = messages.get(seq);
            jdbc.sql("""
                            INSERT INTO chat_message (conversation_id, seq, type, content, tool_calls, tool_responses)
                            VALUES (?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB))""")
                    .params(conversationId, seq, message.getMessageType().name(), message.getText(),
                            toolCalls(message), toolResponses(message))
                    .update();
        }
    }

    /** Appends messages to the permanent archive of the conversation. */
    public void archive(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            jdbc.sql("""
                            INSERT INTO chat_archive (conversation_id, type, content, tool_calls, tool_responses)
                            VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB))""")
                    .params(conversationId, message.getMessageType().name(), message.getText(),
                            toolCalls(message), toolResponses(message))
                    .update();
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbc.sql("DELETE FROM chat_message WHERE conversation_id = ?").param(conversationId).update();
    }

    private Message toMessage(MessageType type, String content, String toolCalls, String toolResponses) {
        return switch (type) {
            case USER -> UserMessage.builder().text(content).build();
            case SYSTEM -> SystemMessage.builder().text(content).build();
            case ASSISTANT -> AssistantMessage.builder()
                    .content(content)
                    .toolCalls(toolCalls == null ? List.of() : json.readValue(toolCalls, TOOL_CALLS))
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(toolResponses == null ? List.of() : json.readValue(toolResponses, TOOL_RESPONSES))
                    .build();
        };
    }

    private String toolCalls(Message message) {
        return message instanceof AssistantMessage assistant && assistant.hasToolCalls()
                ? json.writeValueAsString(assistant.getToolCalls())
                : null;
    }

    private String toolResponses(Message message) {
        return message instanceof ToolResponseMessage tool ? json.writeValueAsString(tool.getResponses()) : null;
    }
}
