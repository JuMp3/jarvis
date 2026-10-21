package dev.jarvis.mail;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * IMAP and SMTP servers of a mailbox: from the settings ("host" or "host:port"), or from the domain of the
 * address for the well-known providers. Pure logic.
 */
public final class MailServers {

    public static final int IMAPS_PORT = 993;
    public static final int SUBMISSION_PORT = 587;

    public record Endpoint(String host, int port) {

        /** Port 465 is TLS from the first byte; the others (587, 25) upgrade with STARTTLS. */
        public boolean implicitTls() {
            return port == 465 || port == IMAPS_PORT;
        }
    }

    private record Preset(Endpoint imap, Endpoint smtp) {
    }

    private static final Preset GMAIL = new Preset(new Endpoint("imap.gmail.com", 993), new Endpoint("smtp.gmail.com", 587));
    private static final Preset ICLOUD = new Preset(new Endpoint("imap.mail.me.com", 993), new Endpoint("smtp.mail.me.com", 587));
    private static final Preset YAHOO = new Preset(new Endpoint("imap.mail.yahoo.com", 993), new Endpoint("smtp.mail.yahoo.com", 465));
    private static final Preset LIBERO = new Preset(new Endpoint("imapmail.libero.it", 993), new Endpoint("smtp.libero.it", 465));
    private static final Preset FASTMAIL = new Preset(new Endpoint("imap.fastmail.com", 993), new Endpoint("smtp.fastmail.com", 465));
    private static final Preset GMX = new Preset(new Endpoint("imap.gmx.com", 993), new Endpoint("mail.gmx.com", 587));

    private static final Map<String, Preset> BY_DOMAIN = Map.ofEntries(
            Map.entry("gmail.com", GMAIL), Map.entry("googlemail.com", GMAIL),
            Map.entry("icloud.com", ICLOUD), Map.entry("me.com", ICLOUD), Map.entry("mac.com", ICLOUD),
            Map.entry("yahoo.com", YAHOO), Map.entry("yahoo.it", YAHOO),
            Map.entry("libero.it", LIBERO), Map.entry("inwind.it", LIBERO), Map.entry("iol.it", LIBERO),
            Map.entry("blu.it", LIBERO),
            Map.entry("fastmail.com", FASTMAIL), Map.entry("gmx.com", GMX), Map.entry("gmx.net", GMX));

    private MailServers() {
    }

    /** @throws IllegalArgumentException if the server is not set and the provider is not known */
    public static Endpoint imap(String address, String configured) {
        return resolve(configured, IMAPS_PORT, address, Preset::imap, "IMAP");
    }

    /** @throws IllegalArgumentException if the server is not set and the provider is not known */
    public static Endpoint smtp(String address, String configured) {
        return resolve(configured, SUBMISSION_PORT, address, Preset::smtp, "SMTP");
    }

    private static Endpoint resolve(String configured, int defaultPort, String address,
                                    Function<Preset, Endpoint> pick, String protocol) {
        if (configured != null && !configured.isBlank()) {
            String value = configured.strip();
            int colon = value.lastIndexOf(':');
            if (colon > 0) {
                try {
                    return new Endpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Server " + protocol + " non valido: " + value);
                }
            }
            return new Endpoint(value, defaultPort);
        }
        Preset preset = BY_DOMAIN.get(domain(address));
        if (preset == null) {
            throw new IllegalArgumentException("Non conosco i server di " + domain(address) + ": indica il server "
                    + protocol + " nelle impostazioni (host oppure host:porta).");
        }
        return pick.apply(preset);
    }

    private static String domain(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        return at < 0 ? "" : address.substring(at + 1).strip().toLowerCase(Locale.ROOT);
    }
}
