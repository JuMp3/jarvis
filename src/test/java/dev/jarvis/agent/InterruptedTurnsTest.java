package dev.jarvis.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class InterruptedTurnsTest {

    private static final Message QUESTION = UserMessage.builder().text("Raccontami la storia di Iron Man").build();

    @Test
    void completedTurnNeedsNothing() {
        List<Message> history = List.of(QUESTION, AssistantMessage.builder().content("Tony Stark è...").build());

        assertThat(InterruptedTurns.closingMessages(history, "Tony Stark è...")).isEmpty();
        assertThat(InterruptedTurns.closingMessages(List.of(), "")).isEmpty();
    }

    @Test
    void unansweredQuestionGetsThePartialAnswer() {
        List<Message> closing = InterruptedTurns.closingMessages(List.of(QUESTION), "Tony Stark è un inventore ");

        assertThat(closing).singleElement()
                .extracting(Message::getText)
                .isEqualTo("Tony Stark è un inventore " + InterruptedTurns.NOTE);
    }

    @Test
    void danglingToolCallGetsAResultBeforeTheNote() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "saveNote", "{}");
        List<Message> history = List.of(QUESTION, AssistantMessage.builder().content("").toolCalls(List.of(call)).build());

        List<Message> closing = InterruptedTurns.closingMessages(history, "");

        assertThat(closing).hasSize(2);
        assertThat(((ToolResponseMessage) closing.get(0)).getResponses())
                .containsExactly(new ToolResponseMessage.ToolResponse("call-1", "saveNote", InterruptedTurns.TOOL_RESULT));
        assertThat(closing.get(1).getText()).isEqualTo(InterruptedTurns.NOTE);
    }
}
