package dev.jarvis.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClaudeCodeSpecialistTest {

    @Test
    void slugMakesValidBranchNames() {
        assertThat(ClaudeCodeSpecialist.slug("Aggiungi l'endpoint /health — più veloce!")).isEqualTo("aggiungi-l-endpoint-health-piu");
        assertThat(ClaudeCodeSpecialist.slug("Città & caffè")).isEqualTo("citta-caffe");
        assertThat(ClaudeCodeSpecialist.slug("???")).isEqualTo("task");
    }
}
