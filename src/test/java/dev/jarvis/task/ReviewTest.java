package dev.jarvis.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewTest {

    @Test
    void readsTheVerdictFromItsFirstLine() {
        assertThat(Review.of("OK").ok()).isTrue();
        assertThat(Review.of("ok, va bene").ok()).isTrue();
        assertThat(Review.of("").ok()).as("a reviewer that does not answer does not block").isTrue();

        Review below = Review.of("RIVEDERE\n- manca la fonte\n- date incoerenti");
        assertThat(below.ok()).isFalse();
        assertThat(below.notes()).isEqualTo("- manca la fonte\n- date incoerenti");
        assertThat(Review.of("RIVEDERE: manca il prezzo").notes()).isEqualTo("manca il prezzo");
        assertThat(Review.of("REVISE").notes()).isNotBlank();
    }

    @Test
    void addsTheTokensOfBothCalls() {
        var sum = Review.sum(new Specialists.Result("a", 10, 5, null), new Specialists.Result("b", 3, null, null), "a");
        assertThat(sum.inputTokens()).isEqualTo(13);
        assertThat(sum.outputTokens()).isEqualTo(5);
        assertThat(sum.costUsd()).isNull();
        assertThat(sum.text()).isEqualTo("a");
    }
}
