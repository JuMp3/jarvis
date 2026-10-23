package dev.jarvis.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class UtteranceTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Test
    void piecesAreTranscribedInOrderEachWithTheTextBeforeIt() throws Exception {
        List<String> contexts = new CopyOnWriteArrayList<>();
        Utterance utterance = new Utterance(executor);
        for (String piece : List.of("Jarvis, che tempo fa", "domani a Milano", "")) {
            utterance.add(before -> {
                contexts.add(before);
                sleepIfFirst(before); // a slow first piece must not let the second one overtake it
                return Utterance.join(before, piece);
            });
        }
        assertThat(utterance.text().get()).isEqualTo("Jarvis, che tempo fa domani a Milano");
        assertThat(contexts).containsExactly("", "Jarvis, che tempo fa", "Jarvis, che tempo fa domani a Milano");
    }

    @Test
    void aFailedPieceFailsThePhrase() {
        Utterance utterance = new Utterance(executor);
        utterance.add(before -> {
            throw new IllegalStateException("whisper down");
        });
        utterance.add(before -> Utterance.join(before, "dopo"));
        assertThatThrownBy(() -> utterance.text().get()).isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("whisper down");
    }

    @Test
    void anEmptyPhraseStaysEmpty() throws Exception {
        assertThat(new Utterance(executor).text().get()).isEmpty();
        assertThat(Utterance.join("", "  ")).isEmpty();
    }

    private static void sleepIfFirst(String before) {
        if (before.isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
