package dev.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MailServersTest {

    @Test
    void knownProvidersNeedNoServerSettings() {
        assertThat(MailServers.imap("mario@Gmail.com", null)).isEqualTo(new MailServers.Endpoint("imap.gmail.com", 993));
        assertThat(MailServers.smtp("mario@libero.it", "")).isEqualTo(new MailServers.Endpoint("smtp.libero.it", 465));
        assertThat(MailServers.smtp("mario@libero.it", "").implicitTls()).isTrue();
        assertThat(MailServers.smtp("mario@icloud.com", null).implicitTls()).isFalse();
    }

    @Test
    void configuredServersWinWithOptionalPort() {
        assertThat(MailServers.imap("me@azienda.it", "mail.azienda.it")).isEqualTo(new MailServers.Endpoint("mail.azienda.it", 993));
        assertThat(MailServers.smtp("me@gmail.com", "relay.azienda.it:2525")).isEqualTo(new MailServers.Endpoint("relay.azienda.it", 2525));
    }

    @Test
    void unknownProviderAsksForTheServer() {
        assertThatThrownBy(() -> MailServers.imap("me@azienda.it", null)).hasMessageContaining("azienda.it");
        assertThatThrownBy(() -> MailServers.smtp("me@azienda.it", "host:abc")).hasMessageContaining("non valido");
    }
}
