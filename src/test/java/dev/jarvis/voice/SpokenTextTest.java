package dev.jarvis.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpokenTextTest {

    /** Feeds the answer in small tokens, as a model streams it. */
    private static String spoken(String answer, int tokenSize) {
        SpokenText filter = new SpokenText();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < answer.length(); i += tokenSize) {
            out.append(filter.append(answer.substring(i, Math.min(answer.length(), i + tokenSize))));
        }
        return out.append(filter.flush()).toString();
    }

    @Test
    void cardsCodeAndTablesAreShownNotSpoken() {
        String answer = """
                Domani a Milano sole, poi pioggia sabato.
                ```card
                {"type":"weather","place":"Milano"}
                ```
                | Giorno | Max |
                |---|---|
                | ven | 19 |
                Porta l'ombrello.""";
        for (int size : new int[] {1, 3, 7, 200}) {
            assertThat(spoken(answer, size)).as("tokens of " + size)
                    .isEqualTo("Domani a Milano sole, poi pioggia sabato.\nPorta l'ombrello.");
        }
    }

    @Test
    void inlineCodeAtTheStartOfALineIsStillSpoken() {
        assertThat(spoken("`npm` è installato.\n- e `node` anche", 2)).isEqualTo("`npm` è installato.\n- e `node` anche");
    }

    @Test
    void anUnclosedFenceSilencesTheRest() {
        assertThat(spoken("Ecco:\n```json\n{\"a\": 1}", 4)).isEqualTo("Ecco:\n");
    }
}
