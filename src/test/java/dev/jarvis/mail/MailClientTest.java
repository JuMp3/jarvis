package dev.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

/** Real IMAP and SMTP servers (GreenMail), without TLS: the only case the client allows it. */
class MailClientTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String ME = "giampiero@example.com";
    private MailClient client;

    @BeforeEach
    void setUp() {
        MAIL.setUser(ME, ME, "app-password");
        client = new MailClient(ME, "app-password",
                new MailServers.Endpoint("127.0.0.1", MAIL.getImap().getPort()),
                new MailServers.Endpoint("127.0.0.1", MAIL.getSmtp().getPort()), false);
        GreenMailUtil.sendTextEmailTest(ME, "marta@example.com", "Budget Orione", "Il budget approvato è 48.000 euro.");
        GreenMailUtil.sendMessageBody(ME, "news@example.com", "Offerte della settimana",
                "<html><body><p>Sconti</p><script>x()</script><p>fino al 50%</p></body></html>", "text/html",
                ServerSetupTest.SMTP);
    }

    @Test
    void listsMostRecentFirstAndReadingDoesNotMarkAsRead() throws Exception {
        List<MailClient.Summary> recent = client.recent(10);

        assertThat(recent).extracting(MailClient.Summary::subject).containsExactly("Offerte della settimana", "Budget Orione");
        MailClient.Content budget = client.read(recent.get(1).uid());
        assertThat(budget.text()).contains("48.000 euro");
        assertThat(client.recent(10)).allSatisfy(mail -> assertThat(mail.seen()).isFalse());
    }

    @Test
    void searchesAndReducesHtmlToText() throws Exception {
        List<MailClient.Summary> found = client.search("Offerte", 10);

        assertThat(found).singleElement().extracting(MailClient.Summary::from).asString().contains("news@example.com");
        String text = client.read(found.getFirst().uid()).text();
        assertThat(text).contains("Sconti").contains("fino al 50%").doesNotContain("<p>").doesNotContain("x()");
    }

    @Test
    void sendsAndRepliesInTheSameThread() throws Exception {
        client.send("marta@example.com", "", "Riunione", "Ci vediamo alle 10.");
        long budget = client.search("Budget", 10).getFirst().uid();
        String to = client.reply(budget, "Ricevuto, grazie.");

        assertThat(to).contains("marta@example.com");
        MimeMessage[] delivered = MAIL.getReceivedMessagesForDomain("example.com");
        MimeMessage reply = List.of(delivered).stream()
                .filter(message -> GreenMailUtil.getBody(message).contains("Ricevuto")).findFirst().orElseThrow();
        assertThat(reply.getSubject()).isEqualTo("Re: Budget Orione");
        assertThat(reply.getHeader("In-Reply-To")).isNotEmpty();
        assertThat(reply.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("marta@example.com");
    }

    @Test
    void wrongPasswordIsRejected() {
        MailClient wrong = new MailClient(ME, "sbagliata", new MailServers.Endpoint("127.0.0.1", MAIL.getImap().getPort()),
                new MailServers.Endpoint("127.0.0.1", MAIL.getSmtp().getPort()), false);
        assertThatThrownBy(wrong::check).isInstanceOf(AuthenticationFailedException.class);
    }
}
