package dev.jarvis.mail;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SubjectTerm;

/**
 * A mailbox over IMAP (reading) and SMTP (sending), with an address and an app password. Connections are
 * always encrypted (TLS, or STARTTLS made mandatory); plain text exists only for the test servers.
 */
public final class MailClient {

    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_TEXT = 20_000;

    /** @param uid stable id of the message in its folder, to read or answer it */
    public record Summary(long uid, String from, String subject, Instant date, boolean seen) {
    }

    public record Content(Summary summary, String to, String text) {
    }

    private final String address;
    private final String password;
    private final MailServers.Endpoint imap;
    private final MailServers.Endpoint smtp;
    private final boolean encrypted;

    public MailClient(String address, String password, MailServers.Endpoint imap, MailServers.Endpoint smtp) {
        this(address, password, imap, smtp, true);
    }

    /** @param encrypted false only for the test servers */
    MailClient(String address, String password, MailServers.Endpoint imap, MailServers.Endpoint smtp, boolean encrypted) {
        this.address = address;
        this.password = password;
        this.imap = imap;
        this.smtp = smtp;
        this.encrypted = encrypted;
    }

    public String address() {
        return address;
    }

    /** Logs in to IMAP and SMTP, for the "Verifica" button. */
    public void check() throws MessagingException {
        try (Store store = store()) {
            store.getFolder("INBOX").exists();
        }
        try (Transport transport = session().getTransport(smtpProtocol())) {
            transport.connect(smtp.host(), smtp.port(), address, password);
        }
    }

    public List<Summary> recent(int limit) throws MessagingException {
        try (Store store = store(); Folder inbox = open(store)) {
            int count = inbox.getMessageCount();
            Message[] messages = count == 0 ? new Message[0] : inbox.getMessages(Math.max(1, count - limit + 1), count);
            return summaries(inbox, messages);
        }
    }

    public List<Summary> search(String text, int limit) throws MessagingException {
        try (Store store = store(); Folder inbox = open(store)) {
            Message[] found = inbox.search(new OrTerm(new SubjectTerm(text),
                    new OrTerm(new FromStringTerm(text), new BodyTerm(text))));
            Message[] last = Arrays.copyOfRange(found, Math.max(0, found.length - limit), found.length);
            return summaries(inbox, last);
        }
    }

    public Content read(long uid) throws MessagingException, IOException {
        try (Store store = store(); Folder inbox = open(store)) {
            Message message = ((UIDFolder) inbox).getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("Nessun messaggio con id " + uid);
            }
            String text = text(message);
            return new Content(summary(inbox, message), join(message.getRecipients(Message.RecipientType.TO)),
                    text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n… testo troncato");
        }
    }

    /** @param cc may be blank */
    public void send(String to, String cc, String subject, String body) throws MessagingException {
        MimeMessage message = new MimeMessage(session());
        message.setFrom(new InternetAddress(address));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
        if (cc != null && !cc.isBlank()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc, true));
        }
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.setSentDate(new Date());
        deliver(message);
    }

    /** Answers the sender of a message, in the same thread. */
    public String reply(long uid, String body) throws MessagingException {
        try (Store store = store(); Folder inbox = open(store)) {
            Message original = ((UIDFolder) inbox).getMessageByUID(uid);
            if (original == null) {
                throw new MessagingException("Nessun messaggio con id " + uid);
            }
            MimeMessage answer = (MimeMessage) original.reply(false);
            answer.setFrom(new InternetAddress(address));
            answer.setText(body, "UTF-8");
            answer.setSentDate(new Date());
            deliver(answer);
            return join(answer.getRecipients(Message.RecipientType.TO));
        }
    }

    private void deliver(MimeMessage message) throws MessagingException {
        try (Transport transport = session().getTransport(smtpProtocol())) {
            transport.connect(smtp.host(), smtp.port(), address, password);
            message.saveChanges();
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    private Store store() throws MessagingException {
        Store store = session().getStore(encrypted ? "imaps" : "imap");
        store.connect(imap.host(), imap.port(), address, password);
        return store;
    }

    private static Folder open(Store store) throws MessagingException {
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY); // reading never marks messages as read
        return inbox;
    }

    private String smtpProtocol() {
        return encrypted && smtp.implicitTls() ? "smtps" : "smtp";
    }

    private Session session() {
        Properties properties = new Properties();
        for (String protocol : List.of("imap", "imaps", "smtp", "smtps")) {
            properties.put("mail." + protocol + ".connectiontimeout", TIMEOUT_MS);
            properties.put("mail." + protocol + ".timeout", TIMEOUT_MS);
            properties.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        }
        // Fetch bodies with PEEK: reading on the user's behalf must not mark messages as read
        properties.put("mail.imap.peek", "true");
        properties.put("mail.imaps.peek", "true");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtps.auth", "true");
        if (encrypted) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        return Session.getInstance(properties);
    }

    private static List<Summary> summaries(Folder folder, Message[] messages) throws MessagingException {
        List<Summary> summaries = new ArrayList<>();
        for (int i = messages.length - 1; i >= 0; i--) { // most recent first
            summaries.add(summary(folder, messages[i]));
        }
        return summaries;
    }

    private static Summary summary(Folder folder, Message message) throws MessagingException {
        Date date = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        return new Summary(((UIDFolder) folder).getUID(message), join(message.getFrom()), message.getSubject(),
                date == null ? null : date.toInstant(), message.isSet(Flags.Flag.SEEN));
    }

    private static String join(Address[] addresses) {
        return addresses == null ? "" : String.join(", ", Arrays.stream(addresses).map(Address::toString).toList());
    }

    /** Plain text of a message: text/plain preferred, HTML reduced to text. */
    static String text(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return part.getContent().toString();
        }
        if (part.isMimeType("text/html")) {
            return part.getContent().toString()
                    .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?i)<br\\s*/?>|</p>|</div>", "\n")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                    .replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").strip();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String html = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) {
                    continue;
                }
                if (child.isMimeType("text/plain") || child.isMimeType("multipart/*")) {
                    String text = text(child);
                    if (!text.isBlank()) {
                        return text;
                    }
                } else if (child.isMimeType("text/html") && html == null) {
                    html = text(child);
                }
            }
            return html == null ? "" : html;
        }
        return "";
    }
}
