package dev.jarvis.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SentenceChunkerTest {

    private static List<String> feed(SentenceChunker chunker, String... tokens) {
        List<String> out = new ArrayList<>();
        for (String token : tokens) {
            out.addAll(chunker.append(token));
        }
        out.addAll(chunker.flush());
        return out;
    }

    @Test
    void emitsSentenceAsSoonAsItIsFollowedByWhitespace() {
        SentenceChunker chunker = new SentenceChunker();
        assertTrue(chunker.append("Ciao, è tutto pronto").isEmpty());
        assertTrue(chunker.append(".").isEmpty(), "needs the following whitespace to be sure");
        assertEquals(List.of("Ciao, è tutto pronto."), chunker.append(" Poi"));
    }

    @Test
    void splitsTokenStreamIntoSentences() {
        List<String> sentences = feed(new SentenceChunker(),
                "Oggi a Milano ", "ci sono 18 gradi. ", "Domani pioverà! ", "Vuoi i dettagli", "?");
        assertEquals(List.of("Oggi a Milano ci sono 18 gradi.", "Domani pioverà!", "Vuoi i dettagli?"), sentences);
    }

    @Test
    void doesNotSplitDecimals() {
        List<String> sentences = feed(new SentenceChunker(), "La versione 3.5 è uscita ieri. Ottimo.");
        assertEquals(List.of("La versione 3.5 è uscita ieri.", "Ottimo."), sentences);
    }

    @Test
    void mergesVeryShortSentencesWithTheNextOne() {
        List<String> sentences = feed(new SentenceChunker(), "Sì. Ho salvato la nota per te. ");
        assertEquals(List.of("Sì. Ho salvato la nota per te."), sentences);
    }

    @Test
    void splitsOnLineBreaksAndStripsMarkdown() {
        List<String> sentences = feed(new SentenceChunker(),
                "**Ecco** le opzioni principali\n- prima opzione disponibile\n- seconda opzione `veloce`\n");
        assertEquals(List.of("Ecco le opzioni principali", "prima opzione disponibile", "seconda opzione veloce"),
                sentences);
    }

    @Test
    void splitsLongTextWithoutPunctuationAtSpaces() {
        SentenceChunker chunker = new SentenceChunker(5, 20);
        List<String> sentences = feed(chunker, "uno due tre quattro cinque sei sette otto nove");
        assertTrue(sentences.size() > 1);
        sentences.forEach(s -> assertTrue(s.length() <= 20, s));
        assertEquals("uno due tre quattro cinque sei sette otto nove", String.join(" ", sentences));
    }

    @Test
    void forSpeechKeepsLinkTextOnly() {
        assertEquals("Leggi la guida qui", SentenceChunker.forSpeech("Leggi la [guida](https://example.com) qui"));
    }
}
