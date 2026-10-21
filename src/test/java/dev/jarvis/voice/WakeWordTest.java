package dev.jarvis.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WakeWordTest {

    @Test
    void keepsTheRequestAfterTheName() {
        assertThat(WakeWord.strip("Jarvis, che ore sono?", "jarvis")).contains("che ore sono?");
        assertThat(WakeWord.strip("Ehi Jarvis apri la posta", "jarvis")).contains("apri la posta");
        assertThat(WakeWord.strip("Jarvis", "jarvis")).contains("");
    }

    @Test
    void forgivesHowSpeechToTextSpellsIt() {
        assertThat(WakeWord.strip("Giarvis, accendi la luce", "jarvis")).contains("accendi la luce");
        assertThat(WakeWord.strip("Jervis che tempo fa", "jarvis")).contains("che tempo fa");
        assertThat(WakeWord.strip("JARVIS! ferma tutto", "jarvis")).contains("ferma tutto");
    }

    @Test
    void ignoresPhrasesNotAddressedToJarvis() {
        assertThat(WakeWord.strip("passami il sale per favore", "jarvis")).isEmpty();
        assertThat(WakeWord.strip("ho parlato con Marco di Jarvis ieri", "jarvis")).isEmpty(); // too far in the phrase
        assertThat(WakeWord.strip("", "jarvis")).isEmpty();
        assertThat(WakeWord.strip("Jarvis ciao", "")).isEmpty();
    }
}
