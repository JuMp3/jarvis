package dev.jarvis.settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jarvis.mail.MailClient;
import dev.jarvis.mail.MailServers;
import dev.jarvis.settings.SettingsCatalog.Definition;
import dev.jarvis.settings.SettingsCatalog.Kind;

/**
 * Reads and saves the settings of the wizard and the Settings section. Secrets are encrypted before they reach
 * the database and never leave the server again: the page only learns whether they are set, and where from.
 */
@Service
public class SettingsService {

    public static final String SETUP_COMPLETED = "jarvis.setup.completed";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** @param value current value (text, or list of strings); null for secrets */
    public record View(String name, String group, String label, String help, String kind, List<String> choices,
                       Object value, boolean set, String origin, String test) {
    }

    public record TestResult(boolean ok, String message) {
    }

    private final JdbcClient jdbc;
    private final ConfigurableEnvironment environment;
    private final SecretBox box;

    public SettingsService(JdbcClient jdbc, ConfigurableEnvironment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.box = new SecretBox(MasterKey.load(environment.getProperty(MasterKey.ENVIRONMENT_VARIABLE),
                Path.of(environment.getProperty("jarvis.settings.key-file",
                        System.getProperty("user.home") + "/.jarvis/master.key"))));
    }

    public boolean setupRequired() {
        return !environment.getProperty(SETUP_COMPLETED, Boolean.class, false);
    }

    public List<View> view() {
        return SettingsCatalog.ALL.stream().map(this::view).toList();
    }

    private View view(Definition definition) {
        String origin = origin(definition.property());
        Object value = switch (definition.kind()) {
            case SECRET -> null;
            case LIST, MULTI_CHOICE -> Binder.get(environment)
                    .bind(definition.property(), Bindable.listOf(String.class)).orElse(List.of());
            case TEXT, CHOICE -> environment.getProperty(definition.property(),
                    definition.kind() == Kind.CHOICE ? definition.choices().getFirst() : "");
        };
        boolean set = definition.kind() == Kind.SECRET
                ? !environment.getProperty(definition.property(), "").isBlank() : value != null;
        return new View(definition.name(), definition.group(), definition.label(), definition.help(),
                definition.kind().name().toLowerCase(Locale.ROOT), definition.choices(), value, set, origin,
                definition.test());
    }

    /** Where the effective value comes from: the database, an environment variable or a configuration file. */
    private String origin(String property) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object found = source.getProperty(property);
            if (found == null && source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(property + "[")) {
                        found = name;
                        break;
                    }
                }
            }
            if (found != null && !found.toString().isBlank()) {
                if (source.getName().equals(DatabaseSettingsSource.NAME)) {
                    return "settings";
                }
                if (source.getName().equals(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
                    return "environment";
                }
                return "file";
            }
        }
        return null;
    }

    /**
     * Validates and saves. Text and lists left empty go back to the default; secrets left empty stay as they
     * are, unless listed in {@code cleared}. Marks the setup as completed.
     *
     * @throws IllegalArgumentException with a message for the user if a value is not valid
     */
    @Transactional
    public void save(Map<String, Object> values, Collection<String> cleared) {
        List<Runnable> writes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Definition definition = SettingsCatalog.find(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Impostazione sconosciuta: " + entry.getKey()));
            writes.add(write(definition, entry.getValue()));
        }
        for (String name : cleared) {
            Definition definition = SettingsCatalog.find(name)
                    .orElseThrow(() -> new IllegalArgumentException("Impostazione sconosciuta: " + name));
            writes.add(() -> delete(definition.name()));
        }
        writes.forEach(Runnable::run); // only once everything is valid
        upsert(SETUP_COMPLETED, SettingsRows.TEXT, "true");
    }

    private Runnable write(Definition definition, Object raw) {
        String label = definition.label();
        return switch (definition.kind()) {
            case SECRET -> {
                String secret = raw == null ? "" : raw.toString().strip();
                yield secret.isEmpty() ? () -> { }
                        : () -> upsert(definition.name(), SettingsRows.SECRET, box.encrypt(definition.name(), secret));
            }
            case TEXT -> {
                String text = raw == null ? "" : raw.toString().strip();
                yield text.isEmpty() ? () -> delete(definition.name())
                        : () -> upsert(definition.name(), SettingsRows.TEXT, text);
            }
            case CHOICE -> {
                String choice = raw == null ? "" : raw.toString().strip();
                if (!definition.choices().contains(choice)) {
                    throw new IllegalArgumentException(label + ": scegli tra " + definition.choices() + ".");
                }
                yield SettingsCatalog.MAIL.equals(definition.name()) ? () -> applyMail(choice)
                        : () -> upsert(definition.name(), SettingsRows.TEXT, choice);
            }
            case MULTI_CHOICE -> {
                List<String> chosen = items(raw, true);
                if (chosen.isEmpty() || !definition.choices().containsAll(chosen)) {
                    throw new IllegalArgumentException(label + ": scegli almeno uno tra " + definition.choices() + ".");
                }
                yield () -> upsert(definition.name(), SettingsRows.TEXT, String.join(",", chosen));
            }
            case LIST -> {
                List<String> folders = items(raw, false);
                if (definition.name().equals("jarvis.agents.coder.workspaces")) {
                    folders.stream().filter(folder -> !Files.isDirectory(Path.of(folder))).findFirst()
                            .ifPresent(missing -> {
                                throw new IllegalArgumentException(label + ": la cartella " + missing + " non esiste.");
                            });
                }
                yield folders.isEmpty() ? () -> delete(definition.name())
                        : () -> upsert(definition.name(), SettingsRows.LIST, String.join("\n", folders));
            }
        };
    }

    private void applyMail(String provider) {
        MailTemplates.PREFIXES.forEach(prefix ->
                jdbc.sql("DELETE FROM setting WHERE name LIKE ?").param(prefix + "%").update());
        upsert(SettingsCatalog.MAIL, SettingsRows.TEXT, provider);
        String caCerts = environment.getProperty("NODE_EXTRA_CA_CERTS");
        Map<String, MailTemplates.Row> rows = switch (provider) {
            case "outlook" -> MailTemplates.outlook(WINDOWS, caCerts);
            case "microsoft365" -> MailTemplates.microsoft365(WINDOWS, caCerts);
            case "imap" -> MailTemplates.imap();
            default -> Map.of();
        };
        rows.forEach((name, row) -> upsert(name, row.kind(), row.value()));
    }

    /**
     * The secret in use, in clear, for the "show" button of the Settings section. Only on explicit request and
     * one at a time: the list of settings never carries secrets. In the database it stays encrypted.
     */
    public String reveal(String name) {
        Definition definition = SettingsCatalog.find(name).filter(found -> found.kind() == Kind.SECRET)
                .orElseThrow(() -> new IllegalArgumentException("Non è una chiave: " + name));
        return environment.getProperty(definition.property(), "");
    }

    /** Checks a key without saving it; with an empty value it checks the one in use. */
    public TestResult test(String name, String value) {
        Definition definition = SettingsCatalog.find(name).filter(found -> found.test() != null)
                .orElseThrow(() -> new IllegalArgumentException("Nessuna verifica per " + name));
        String secret = value == null || value.isBlank() ? environment.getProperty(definition.property(), "") : value.strip();
        if (secret.isBlank()) {
            return new TestResult(false, "Nessuna chiave da verificare.");
        }
        try {
            return switch (definition.test()) {
                case "anthropic" -> {
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models"))
                            .header("x-api-key", secret).header("anthropic-version", "2023-06-01");
                    String workspace = environment.getProperty("spring.ai.anthropic.custom-headers.anthropic-workspace-id", "");
                    if (!workspace.isBlank()) {
                        request.header("anthropic-workspace-id", workspace);
                    }
                    yield check(request, "Claude");
                }
                case "openai" -> check(HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
                        .header("Authorization", "Bearer " + secret), "OpenAI");
                case "imap" -> {
                    String address = environment.getProperty("jarvis.mail.address", "");
                    MailClient mailbox = new MailClient(address, secret,
                            MailServers.imap(address, environment.getProperty("jarvis.mail.imap-server")),
                            MailServers.smtp(address, environment.getProperty("jarvis.mail.smtp-server")));
                    mailbox.check();
                    yield new TestResult(true, "Accesso riuscito alla posta di " + address + " (IMAP e SMTP).");
                }
                case "github" -> check(HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                        .header("Authorization", "Bearer " + secret)
                        .header("Accept", "application/vnd.github+json"), "GitHub");
                default -> new TestResult(false, "Verifica non disponibile.");
            };
        } catch (Exception e) {
            return new TestResult(false, "Non riesco a contattare il servizio: " + e.getMessage());
        }
    }

    private static TestResult check(HttpRequest.Builder request, String service) throws Exception {
        HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return switch (response.statusCode()) {
            case 200 -> new TestResult(true, service + " accetta la chiave.");
            case 401, 403 -> new TestResult(false, service + " non accetta la chiave.");
            default -> new TestResult(false, service + " ha risposto " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
        };
    }

    /** @param commas also split on commas (choices); folders are one per line, as a path may contain a comma */
    private static List<String> items(Object raw, boolean commas) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(Object::toString).map(String::strip).filter(item -> !item.isEmpty()).toList();
        }
        return raw == null ? List.of()
                : raw.toString().lines().flatMap(line -> commas ? List.of(line.split(",")).stream() : Stream.of(line))
                .map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private void upsert(String name, String kind, String value) {
        jdbc.sql("""
                        INSERT INTO setting (name, kind, value, updated_at) VALUES (?, ?, ?, now())
                        ON CONFLICT (name) DO UPDATE SET kind = EXCLUDED.kind, value = EXCLUDED.value,
                            updated_at = EXCLUDED.updated_at""")
                .params(name, kind, value).update();
    }

    private void delete(String name) {
        jdbc.sql("DELETE FROM setting WHERE name = ?").param(name).update();
    }
}
