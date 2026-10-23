package dev.jarvis.agent.tools;

import static dev.jarvis.i18n.Text.text;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.AgentService;
import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.i18n.Text;
import dev.jarvis.mail.MailClient;
import dev.jarvis.mail.MailServers;

/**
 * The IMAP/SMTP mailbox of {@code jarvis.mail}. Reading ({@link Reader}) is for the organizer specialist, since
 * email content is untrusted; sending ({@link Sender}) is for Jarvis only, after the user approves the exact
 * message.
 */
@Component
public class MailTools {

    private static final int MAX_LISTED = 20;
    private final DateTimeFormatter when;
    private final String language;
    private final Optional<MailClient> client;
    private final Reader reader = new Reader();
    private final Sender sender;

    public MailTools(JarvisProperties properties, ApprovalService approvals) {
        JarvisProperties.Mail mail = properties.mail();
        this.language = properties.language();
        this.when = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Text.locale(language)).withZone(ZoneId.systemDefault());
        this.client = mail == null || !mail.enabled() || mail.address() == null || mail.address().isBlank()
                ? Optional.empty()
                : Optional.of(new MailClient(mail.address().strip(), mail.password() == null ? "" : mail.password(),
                        MailServers.imap(mail.address(), mail.imapServer()), MailServers.smtp(mail.address(), mail.smtpServer())));
        this.sender = new Sender(approvals);
    }

    public boolean enabled() {
        return client.isPresent();
    }

    public Reader reader() {
        return reader;
    }

    public Sender sender() {
        return sender;
    }

    public final class Reader {

        @Tool(description = "Elenca le email più recenti della posta in arrivo: id, mittente, oggetto, data, se letta.")
        public String listRecentEmails(@ToolParam(description = "Quante, al massimo 20", required = false) Integer limit) {
            return call(mailbox -> describe(mailbox.recent(clamp(limit))));
        }

        @Tool(description = "Cerca nella posta in arrivo per oggetto, mittente o testo.")
        public String searchEmails(@ToolParam(description = "Testo da cercare") String query) {
            return call(mailbox -> {
                List<MailClient.Summary> found = mailbox.search(query, MAX_LISTED);
                return found.isEmpty() ? "Nessuna email trovata per \"" + query + "\"." : describe(found);
            });
        }

        @Tool(description = "Legge il testo di un'email dato il suo id.")
        public String readEmail(@ToolParam(description = "Id dell'email, da listRecentEmails o searchEmails") long id) {
            return call(mailbox -> {
                MailClient.Content content = mailbox.read(id);
                return "Da: " + content.summary().from() + "\nA: " + content.to() + "\nOggetto: "
                        + content.summary().subject() + "\nData: " + when(content.summary()) + "\n\n" + content.text();
            });
        }
    }

    public final class Sender {

        private final ApprovalService approvals;

        Sender(ApprovalService approvals) {
            this.approvals = approvals;
        }

        @Tool(description = "Invia un'email dall'account dell'utente. L'utente deve approvare il messaggio esatto.")
        public String sendEmail(@ToolParam(description = "Destinatari, separati da virgola") String to,
                                @ToolParam(description = "Oggetto") String subject,
                                @ToolParam(description = "Testo") String body,
                                @ToolParam(description = "Copia conoscenza, separati da virgola", required = false) String cc,
                                ToolContext toolContext) {
            String description = text("Inviare un'email da %s\nA: %s%s\nOggetto: %s\n\n%s",
                    "Send an email from %s\nTo: %s%s\nSubject: %s\n\n%s").in(language,
                    client.map(MailClient::address).orElse("?"), to, cc == null || cc.isBlank() ? "" : "\nCc: " + cc,
                    subject, body);
            if (!approve(toolContext, description)) {
                return "L'utente non ha approvato. Nessuna email inviata.";
            }
            return call(mailbox -> {
                mailbox.send(to, cc, subject, body);
                return "Email inviata a " + to + ".";
            });
        }

        @Tool(description = "Risponde al mittente di un'email, nella stessa conversazione. L'utente deve approvare.")
        public String replyToEmail(@ToolParam(description = "Id dell'email a cui rispondere") long id,
                                   @ToolParam(description = "Testo della risposta") String body,
                                   ToolContext toolContext) {
            if (!approve(toolContext, text("Rispondere all'email con id %s:\n\n%s", "Reply to the email with id %s:\n\n%s")
                    .in(language, id, body))) {
                return "L'utente non ha approvato. Nessuna risposta inviata.";
            }
            return call(mailbox -> "Risposta inviata a " + mailbox.reply(id, body) + ".");
        }

        private boolean approve(ToolContext toolContext, String description) {
            return approvals.requestApproval((String) toolContext.getContext().get(AgentService.CONVERSATION_ID_KEY),
                    (String) toolContext.getContext().get(AgentService.SESSION_ID_KEY), description);
        }
    }

    private interface MailCall {
        String run(MailClient mailbox) throws Exception;
    }

    private String call(MailCall call) {
        if (client.isEmpty()) {
            return "La posta IMAP non è configurata (Impostazioni → Account).";
        }
        try {
            return call.run(client.get());
        } catch (Exception e) {
            return "La casella di posta non ha risposto: " + e.getMessage();
        }
    }

    private static int clamp(Integer limit) {
        return limit == null || limit < 1 ? 10 : Math.min(limit, MAX_LISTED);
    }

    private String describe(List<MailClient.Summary> summaries) {
        if (summaries.isEmpty()) {
            return "La posta in arrivo è vuota.";
        }
        return summaries.stream()
                .map(mail -> "- id " + mail.uid() + " | " + when(mail) + " | " + mail.from() + " | " + mail.subject()
                        + (mail.seen() ? "" : " | non letta"))
                .collect(Collectors.joining("\n"));
    }

    private String when(MailClient.Summary mail) {
        return mail.date() == null ? "" : when.format(mail.date());
    }
}
