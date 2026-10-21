package dev.jarvis.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private final SecretBox box = new SecretBox(SecretBox.newKey());

    @Test
    void roundTripsWithoutLeavingThePlaintext() {
        String sealed = box.encrypt("ANTHROPIC_API_KEY", "sk-ant-segreta");

        assertThat(sealed).startsWith("v1:").doesNotContain("segreta");
        assertThat(box.encrypt("ANTHROPIC_API_KEY", "sk-ant-segreta")).isNotEqualTo(sealed); // random IV
        assertThat(box.decrypt("ANTHROPIC_API_KEY", sealed)).isEqualTo("sk-ant-segreta");
    }

    @Test
    void refusesAnotherNameAnotherKeyOrATamperedValue() {
        String sealed = box.encrypt("ANTHROPIC_API_KEY", "sk-ant-segreta");

        assertThatThrownBy(() -> box.decrypt("GITHUB_TOKEN", sealed)).hasMessageContaining("Cannot decrypt");
        assertThatThrownBy(() -> new SecretBox(SecretBox.newKey()).decrypt("ANTHROPIC_API_KEY", sealed))
                .hasMessageContaining("wrong master key");
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> box.decrypt("ANTHROPIC_API_KEY", tampered)).hasMessageContaining("Cannot decrypt");
        assertThatThrownBy(() -> box.decrypt("ANTHROPIC_API_KEY", "in chiaro")).hasMessageContaining("not an encrypted");
        assertThatThrownBy(() -> new SecretBox(new byte[16])).hasMessageContaining("32 bytes");
    }
}
