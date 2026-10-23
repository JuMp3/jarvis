package dev.jarvis.settings;

import static dev.jarvis.i18n.Text.text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.i18n.Text;
import dev.jarvis.mail.MailClient;
import dev.jarvis.mail.MailServers;
import dev.jarvis.routine.Routine;
import dev.jarvis.routine.RoutineRepository;
import dev.jarvis.routine.RoutineSchedule;
import dev.jarvis.voice.AzureSpeech;
import dev.jarvis.voice.TextToSpeech;
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

    /**
     * @param value    current value (text, or list of strings); null for secrets
     * @param when     shown only while these hold, with the values the page has (SettingsCatalog.Condition)
     * @param required while shown it needs a value
     * @param choicesFrom only the choices chosen in this other setting are offered; may be null
     */
    public record View(String name, String group, String label, String help, String kind, List<ChoiceView> choices,
                       Object value, boolean set, String origin, String test, List<SettingsCatalog.Condition> when,
                       boolean required, String choicesFrom) {
    }

    /** @param description may be null */
    public record ChoiceView(String value, String label, String description) {
    }

    public record TestResult(boolean ok, String message) {
    }

    private static final Text UNKNOWN = text("Impostazione sconosciuta: %s", "Unknown setting: %s");

    private final JdbcClient jdbc;
    private final ConfigurableEnvironment environment;
    private final RoutineRepository routines;
    private final SecretBox box;

    public SettingsService(JdbcClient jdbc, ConfigurableEnvironment environment, RoutineRepository routines) {
        this.jdbc = jdbc;
        this.routines = routines;
        this.environment = environment;
        this.box = new SecretBox(MasterKey.load(environment.getProperty(MasterKey.ENVIRONMENT_VARIABLE),
                Path.of(environment.getProperty("jarvis.settings.key-file",
                        System.getProperty("user.home") + "/.jarvis/master.key"))));
    }

    public boolean setupRequired() {
        return !environment.getProperty(SETUP_COMPLETED, Boolean.class, false);
    }

    /** The language in use (Settings → Profilo). */
    public String language() {
        return environment.getProperty(SettingsCatalog.LANGUAGE);
    }

    /** @param language of the texts; null: the one in use (the wizard asks again in the language just chosen) */
    public List<View> view(String language) {
        String in = language != null && SettingsCatalog.LANGUAGES.contains(language) ? language : language();
        return SettingsCatalog.ALL.stream().map(definition -> view(definition, in)).toList();
    }

    private View view(Definition definition, String language) {
        String origin = origin(definition.property());
        Object value = switch (definition.kind()) {
            case SECRET -> null;
            case LIST, MULTI_CHOICE -> Binder.get(environment)
                    .bind(definition.property(), Bindable.listOf(String.class)).orElse(List.of());
            case TEXT, CHOICE -> environment.getProperty(definition.property(),
                    definition.kind() == Kind.CHOICE ? definition.values().getFirst() : "");
            case ROUTINES -> routines.all().stream().map(SettingsService::routineRow).toList();
            case APPS -> Binder.get(environment)
                    .bind(definition.property(), Bindable.mapOf(String.class, JarvisProperties.DesktopApp.class))
                    .map(SettingsRows::appRows).orElse(List.of());
        };
        boolean set = definition.kind() == Kind.SECRET
                ? !environment.getProperty(definition.property(), "").isBlank()
                        && !SettingsCatalog.UNSET.equals(environment.getProperty(definition.property()))
                : value != null;
        List<ChoiceView> choices = definition.choices().stream()
                .map(choice -> new ChoiceView(choice.value(), choice.label().in(language),
                        choice.description() == null ? null : choice.description().in(language)))
                .toList();
        return new View(definition.name(), definition.group().in(language), definition.label().in(language),
                definition.help().in(language), definition.kind().name().toLowerCase(Locale.ROOT), choices, value, set,
                origin, definition.test(), definition.when(), definition.required(), definition.choicesFrom());
    }

    /** Where the effective value comes from: the database, an environment variable or a configuration file. */
    private String origin(String property) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue; // Spring Boot's view over all the others: it would answer first for every key
            }
            if (source.getName().equals(SettingsDefaultsSource.NAME)) {
                return null; // not chosen by anyone yet
            }
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
        // errors in the language just chosen, if any
        Object chosen = values.get(SettingsCatalog.LANGUAGE);
        String language = chosen != null && SettingsCatalog.LANGUAGES.contains(chosen.toString())
                ? chosen.toString() : language();
        List<Runnable> writes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Definition definition = SettingsCatalog.find(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException(UNKNOWN.in(language, entry.getKey())));
            writes.add(write(definition, entry.getValue(), language));
        }
        for (String name : cleared) {
            Definition definition = SettingsCatalog.find(name)
                    .orElseThrow(() -> new IllegalArgumentException(UNKNOWN.in(language, name)));
            writes.add(() -> delete(definition.name()));
        }
        requireShown(values, cleared, language);
        writes.forEach(Runnable::run); // only once everything is valid
        upsert(SETUP_COMPLETED, SettingsRows.TEXT, "true");
    }

    /** A provider chosen without its key (or region, or address) is refused, rather than found off later. */
    private void requireShown(Map<String, Object> values, Collection<String> cleared, String language) {
        Function<String, Object> value = name -> values.containsKey(name) ? values.get(name) : current(name);
        for (Definition definition : SettingsCatalog.ALL) {
            if (definition.required() && definition.shown(value) && !hasValue(definition, values, cleared)) {
                throw new IllegalArgumentException(text("Manca «%s», che serve per quello che hai scelto.",
                        "“%s” is missing: what you chose needs it.").in(language, definition.label().in(language)));
            }
            if (definition.choicesFrom() != null && value.apply(definition.name()) != null
                    && value.apply(definition.choicesFrom()) instanceof Collection<?> offered
                    && !offered.stream().map(String::valueOf).toList().contains(value.apply(definition.name()).toString())) {
                throw new IllegalArgumentException(text("%s: scegli tra i fornitori abilitati.",
                        "%s: choose among the enabled providers.").in(language, definition.label().in(language)));
            }
        }
    }

    private boolean hasValue(Definition definition, Map<String, Object> values, Collection<String> cleared) {
        Object given = values.get(definition.name());
        if (given != null && !given.toString().isBlank()) {
            return true;
        }
        if (cleared.contains(definition.name()) || (definition.kind() != Kind.SECRET && values.containsKey(definition.name()))) {
            return false; // removed, or emptied in the form
        }
        String saved = environment.getProperty(definition.property(), "");
        return !saved.isBlank() && !SettingsCatalog.UNSET.equals(saved);
    }

    /** The value in use of a setting, by name: a list for multiple choices. */
    private Object current(String name) {
        return SettingsCatalog.find(name).map(definition -> definition.kind() == Kind.MULTI_CHOICE
                ? Binder.get(environment).bind(definition.property(), Bindable.listOf(String.class)).orElse(List.of())
                : (Object) environment.getProperty(definition.property())).orElse(null);
    }

    private Runnable write(Definition definition, Object raw, String language) {
        String label = definition.label().in(language);
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
                if (!definition.values().contains(choice)) {
                    throw new IllegalArgumentException(text("%s: scegli tra %s.", "%s: choose one of %s.")
                            .in(language, label, definition.values()));
                }
                yield SettingsCatalog.MAIL.equals(definition.name()) ? () -> applyMail(choice)
                        : () -> upsert(definition.name(), SettingsRows.TEXT, choice);
            }
            case MULTI_CHOICE -> {
                List<String> chosen = items(raw, true);
                if (chosen.isEmpty() || !definition.values().containsAll(chosen)) {
                    throw new IllegalArgumentException(text("%s: scegli almeno uno tra %s.", "%s: choose at least one of %s.")
                            .in(language, label, definition.values()));
                }
                yield () -> upsert(definition.name(), SettingsRows.TEXT, String.join(",", chosen));
            }
            case ROUTINES -> {
                List<Routine> edited = routines(raw, label, language);
                yield () -> routines.replaceAll(edited);
            }
            case APPS -> {
                String apps = SettingsRows.appsJson(raw);
                yield apps.equals("[]") ? () -> delete(definition.name())
                        : () -> upsert(definition.name(), SettingsRows.TEXT, apps);
            }
            case LIST -> {
                List<String> folders = items(raw, false);
                if (definition.name().equals("jarvis.agents.coder.workspaces")) {
                    folders.stream().filter(folder -> !Files.isDirectory(Path.of(folder))).findFirst()
                            .ifPresent(missing -> {
                                throw new IllegalArgumentException(text("%s: la cartella %s non esiste.",
                                        "%s: the folder %s does not exist.").in(language, label, missing));
                            });
                }
                yield folders.isEmpty() ? () -> delete(definition.name())
                        : () -> upsert(definition.name(), SettingsRows.LIST, String.join("\n", folders));
            }
        };
    }

    /** A routine as the Settings section edits it: days as codes, time as HH:mm. */
    private static Map<String, Object> routineRow(Routine routine) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", routine.id());
        row.put("name", routine.name());
        row.put("time", routine.at().toString());
        row.put("days", List.of(RoutineSchedule.format(routine.days()).split(",")));
        row.put("instructions", routine.instructions());
        row.put("enabled", routine.enabled());
        return row;
    }

    /** The rows of the Settings section; empty ones are left out, edited ones keep the day they last ran. */
    private List<Routine> routines(Object raw, String label, String language) {
        List<Routine> edited = new ArrayList<>();
        for (Object item : raw instanceof Collection<?> rows ? rows : List.of()) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String name = String.valueOf(row.get("name") == null ? "" : row.get("name")).strip();
            String instructions = String.valueOf(row.get("instructions") == null ? "" : row.get("instructions")).strip();
            if (name.isEmpty() && instructions.isEmpty()) {
                continue;
            }
            if (name.isEmpty() || instructions.isEmpty()) {
                throw new IllegalArgumentException(text("%s: ogni routine ha un nome e delle istruzioni.",
                        "%s: every routine needs a name and instructions.").in(language, label));
            }
            LocalTime at;
            try {
                at = RoutineSchedule.parseTime(String.valueOf(row.get("time")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(text("%s: «%s» ha un'ora non valida.", "%s: “%s” has an invalid time.")
                        .in(language, label, name));
            }
            List<String> days = items(row.get("days"), true);
            if (days.isEmpty()) {
                throw new IllegalArgumentException(text("%s: scegli almeno un giorno per «%s».",
                        "%s: choose at least one day for “%s”.").in(language, label, name));
            }
            String id = row.get("id") == null || row.get("id").toString().isBlank()
                    ? UUID.randomUUID().toString() : row.get("id").toString();
            Routine before = routines.find(id).orElse(null);
            edited.add(new Routine(id, name, at, RoutineSchedule.parseDays(String.join(",", days)), instructions,
                    !Boolean.FALSE.equals(row.get("enabled")),
                    before != null && before.at().equals(at) ? before.lastRun()
                            : RoutineSchedule.initialLastRun(at, LocalDateTime.now())));
        }
        return edited;
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
                .orElseThrow(() -> new IllegalArgumentException(text("Non è una chiave: %s", "Not a key: %s")
                        .in(language(), name)));
        return environment.getProperty(definition.property(), "");
    }

    /**
     * Checks a key without saving it; with an empty value it checks the one in use.
     *
     * @param form what the page has not saved yet (e.g. the Azure region, the IMAP address), by setting name
     */
    public TestResult test(String name, String value, Map<String, Object> form) {
        String language = language();
        Function<String, String> field = property -> formOr(form, property);
        Definition definition = SettingsCatalog.find(name).filter(found -> found.test() != null)
                .orElseThrow(() -> new IllegalArgumentException(text("Nessuna verifica per %s", "No check for %s")
                        .in(language, name)));
        String secret = value == null || value.isBlank() ? environment.getProperty(definition.property(), "") : value.strip();
        if (secret.isBlank()) {
            return new TestResult(false, text("Nessuna chiave da verificare.", "No key to check.").in(language));
        }
        try {
            return switch (definition.test()) {
                case "anthropic" -> {
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models"))
                            .header("x-api-key", secret).header("anthropic-version", "2023-06-01");
                    String workspace = field.apply("spring.ai.anthropic.custom-headers.anthropic-workspace-id");
                    if (!workspace.isBlank()) {
                        request.header("anthropic-workspace-id", workspace);
                    }
                    yield check(request, "Claude", language);
                }
                case "openai" -> check(HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
                        .header("Authorization", "Bearer " + secret), "OpenAI", language);
                case "groq" -> check(HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/models"))
                        .header("Authorization", "Bearer " + secret), "Groq", language);
                case "imap" -> {
                    String address = field.apply("jarvis.mail.address");
                    MailClient mailbox = new MailClient(address, secret,
                            MailServers.imap(address, field.apply("jarvis.mail.imap-server")),
                            MailServers.smtp(address, field.apply("jarvis.mail.smtp-server")));
                    mailbox.check();
                    yield new TestResult(true, text("Accesso riuscito alla posta di %s (IMAP e SMTP).",
                            "Signed in to the mail of %s (IMAP and SMTP).").in(language, address));
                }
                case "azure" -> {
                    String region = field.apply("jarvis.voice.azure.region");
                    if (region.isBlank()) {
                        yield new TestResult(false, text("Indica prima la regione di Azure.", "Enter the Azure region first.")
                                .in(language));
                    }
                    yield check(HttpRequest.newBuilder(AzureSpeech.voicesUri(region)).header(AzureSpeech.KEY_HEADER, secret),
                            "Azure", language);
                }
                case "github" -> check(HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                        .header("Authorization", "Bearer " + secret)
                        .header("Accept", "application/vnd.github+json"), "GitHub", language);
                default -> new TestResult(false, text("Verifica non disponibile.", "Check not available.").in(language));
            };
        } catch (Exception e) {
            return new TestResult(false, text("Non riesco a contattare il servizio: %s", "I can't reach the service: %s")
                    .in(language, e.getMessage()));
        }
    }

    /** What the page has for a setting (named as its property) and has not saved yet, else the value in use. */
    private String formOr(Map<String, Object> form, String property) {
        Object typed = form == null ? null : form.get(property);
        return typed != null && !typed.toString().isBlank() ? typed.toString().strip() : environment.getProperty(property, "");
    }

    /**
     * A sample of a voice, to hear it before choosing: with the Azure key and region the page has, saved or not.
     *
     * @throws IllegalArgumentException with a message for the user (no key yet, not a voice)
     */
    public TextToSpeech.SpeechAudio preview(String name, String voice, Map<String, Object> form) {
        String language = language();
        SettingsCatalog.find(name).filter(definition -> "voice".equals(definition.test()) && definition.values().contains(voice))
                .orElseThrow(() -> new IllegalArgumentException(text("Nessuna anteprima per %s", "No preview for %s")
                        .in(language, voice)));
        String key = formOr(form, "jarvis.voice.azure.key");
        String region = formOr(form, "jarvis.voice.azure.region");
        if (key.isBlank() || region.isBlank()) {
            throw new IllegalArgumentException(text("Inserisci prima la chiave e la regione di Azure.",
                    "Enter the Azure key and region first.").in(language));
        }
        String spoken = voice.startsWith("en") ? "en" : "it";
        return new AzureSpeech(HTTP, region, key, voice, AzureSpeech.rate(formOr(form, "jarvis.voice.azure.rate"))).synthesize(text("Ciao, sono Jarvis. Potrei parlarti così.",
                "Hello, I'm Jarvis. I could speak to you like this.").in(spoken));
    }

    private static TestResult check(HttpRequest.Builder request, String service, String language) throws Exception {
        HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return switch (response.statusCode()) {
            case 200 -> new TestResult(true, text("%s accetta la chiave.", "%s accepts the key.").in(language, service));
            case 401, 403 -> new TestResult(false, text("%s non accetta la chiave.", "%s does not accept the key.")
                    .in(language, service));
            default -> new TestResult(false, text("%s ha risposto %s: %s", "%s answered %s: %s").in(language, service,
                    response.statusCode(), response.body().substring(0, Math.min(200, response.body().length()))));
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
