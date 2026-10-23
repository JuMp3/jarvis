package dev.jarvis.settings;

import static dev.jarvis.i18n.Text.text;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import dev.jarvis.i18n.Text;

/**
 * The settings the wizard and the Settings section show, in order. Pure data.
 *
 * <p>{@code name} is the key saved in the database; {@code property} is the configuration key the value
 * feeds (DatabaseSettingsSource publishes it under both). Older names such as ANTHROPIC_API_KEY stay as they
 * are: secrets are encrypted with their name as authenticated data, so a row cannot be renamed.
 */
public final class SettingsCatalog {

    /**
     * APPS: the programs Jarvis may start, saved as JSON (see SettingsRows.expandApps). ROUTINES: kept in their own
     * table (RoutineRepository), not in the settings: Jarvis also creates them when asked.
     */
    public enum Kind { TEXT, SECRET, LIST, CHOICE, MULTI_CHOICE, APPS, ROUTINES, AGENTS }

    /**
     * @param group    tab of the Settings section and step of the wizard, in order of first appearance
     * @param test     for a secret, which check the "Verifica" button runs (anthropic, openai, github...); for a
     *                 choice, "voice": each choice has a button to hear it. Null otherwise
     * @param when     shown only if every condition holds (e.g. the Azure key only when the voice is Azure); empty:
     *                 always. A hidden setting keeps its value
     * @param required while shown it must have a value: saving without it is refused
     * @param choicesFrom a multiple choice whose chosen values are the only ones offered here (the default provider
     *                 among the enabled ones); null: all of them
     */
    public record Definition(String name, String property, Text group, Text label, Text help, Kind kind,
                             List<Choice> choices, String test, List<Condition> when, boolean required,
                             String choicesFrom) {

        Definition(String name, String property, Text group, Text label, Text help, Kind kind, List<Choice> choices,
                   String test) {
            this(name, property, group, label, help, kind, choices, test, List.of(), false, null);
        }

        public List<String> values() {
            return choices.stream().map(Choice::value).toList();
        }

        Definition shownWhen(Condition... conditions) {
            return new Definition(name, property, group, label, help, kind, choices, test, List.of(conditions), required,
                    choicesFrom);
        }

        Definition mandatory() {
            return new Definition(name, property, group, label, help, kind, choices, test, when, true, choicesFrom);
        }

        Definition choicesFrom(String setting) {
            return new Definition(name, property, group, label, help, kind, choices, test, when, required, setting);
        }

        /** @param value of a setting by name: a text, or a list for multiple choices; null if not set */
        public boolean shown(Function<String, Object> value) {
            return when.stream().allMatch(condition -> condition.holds(value));
        }
    }

    /**
     * Holds when one of the settings has one of the values (one of them, for a multiple choice): "the OpenAI key when
     * OpenAI is a provider, or does the transcription, or the voice".
     */
    public record Condition(List<String> settings, List<String> values) {

        boolean holds(Function<String, Object> value) {
            return settings.stream().map(value).anyMatch(current -> current instanceof Collection<?> chosen
                    ? chosen.stream().anyMatch(item -> values.contains(String.valueOf(item)))
                    : current != null && values.contains(current.toString()));
        }
    }

    private static Condition when(String setting, String... values) {
        return new Condition(List.of(setting), List.of(values));
    }

    /** An option of a choice: the saved value, and what the user reads (description may be null). */
    public record Choice(String value, Text label, Text description) {
    }

    public static final String MAIL = "JARVIS_MAIL";
    public static final String APPS = "jarvis.desktop.apps";
    public static final String LANGUAGE = "jarvis.language";
    public static final String ROUTINES = "jarvis.routines";
    /** External agents (A2A): saved encrypted, their tokens never leave the server (SettingsRows.expandAgents). */
    public static final String AGENTS = "jarvis.a2a.agents";
    static final String PROVIDERS = "JARVIS_PROVIDERS";
    static final String STT = "JARVIS_VOICE_STT";
    static final String TTS = "JARVIS_VOICE_TTS";
    /** A key not saved yet: lets Spring AI start; the Settings show it as not set. */
    public static final String UNSET = "not-configured";
    /** Languages of the interface and the voice, in the order shown. */
    public static final List<String> LANGUAGES = List.of("it", "en");

    private static final Text PROFILE = text("Profilo", "Profile");
    private static final Text MODELS = text("Modelli", "Models");
    private static final Text VOICE = text("Voce", "Voice");
    private static final Text MAILBOX = text("Posta e calendario", "Mail and calendar");
    private static final Text KEYS = text("Chiavi API", "API keys");
    private static final Text FOLDERS = text("Cartelle", "Folders");
    private static final Text APP_LIST = text("App", "Apps");
    private static final Text ROUTINE_LIST = text("Routine", "Routines");
    private static final Text EXTERNAL = text("Agenti esterni", "External agents");

    public static final List<Definition> ALL = List.of(
            // label in both languages: the user must find it even when the interface does not speak theirs
            new Definition(LANGUAGE, LANGUAGE, PROFILE, text("Lingua · Language", "Language · Lingua"),
                    text("Per l'interfaccia, la voce e le date.", "For the interface, the voice and dates."), Kind.CHOICE,
                    List.of(choice("it", text("Italiano", "Italiano"), null),
                            choice("en", text("English", "English"), null)), null),
            new Definition("jarvis.ui.theme", "jarvis.ui.theme", PROFILE, text("Tema", "Theme"),
                    text("Notte, giorno, o come il sistema.", "Night, day, or as the system."), Kind.CHOICE,
                    List.of(choice("auto", text("Automatico", "Automatic"), text("Come Windows o macOS.", "As Windows or macOS.")),
                            choice("night", text("Notte", "Night"), text("Scuro, con la luce della lampada.", "Dark, lamp-lit.")),
                            choice("day", text("Giorno", "Day"), text("Chiaro e caldo.", "Light and warm."))), null),
            new Definition("jarvis.agent.user-name", "jarvis.agent.user-name", PROFILE,
                    text("Il tuo nome", "Your name"), text("Jarvis ti chiama così.", "What Jarvis calls you."),
                    Kind.TEXT, List.of(), null),
            new Definition(PROVIDERS, "jarvis.providers.enabled", MODELS,
                    text("Fornitori da usare", "Providers to use"),
                    text("Claude è il principale, gli altri sono facoltativi.", "Claude is the main one, the others are optional."),
                    Kind.MULTI_CHOICE,
                    List.of(choice("anthropic", text("Claude", "Claude"),
                                    text("Il modello principale di Jarvis.", "Jarvis' main model.")),
                            choice("openai", text("OpenAI", "OpenAI"), text("Modelli GPT, a pagamento.", "GPT models, paid.")),
                            choice("ollama", text("Ollama", "Ollama"),
                                    text("Modelli sul tuo PC, se hai Ollama.", "Models on your PC, if you have Ollama."))), null),
            new Definition("jarvis.providers.default-provider", "jarvis.providers.default-provider", MODELS,
                    text("Fornitore predefinito", "Default provider"),
                    text("Quello usato all'apertura; si cambia anche in alto.", "The one used at start; you can also change it at the top."),
                    Kind.CHOICE,
                    List.of(choice("anthropic", text("Claude", "Claude"), null),
                            choice("openai", text("OpenAI", "OpenAI"), null),
                            choice("ollama", text("Ollama", "Ollama"), null)), null).choicesFrom(PROVIDERS),
            new Definition("OLLAMA_BASE_URL", "spring.ai.ollama.base-url", MODELS,
                    text("Indirizzo di Ollama", "Ollama address"),
                    text("Di solito http://localhost:11434.", "Usually http://localhost:11434."),
                    Kind.TEXT, List.of(), null).shownWhen(when(PROVIDERS, "ollama")),
            new Definition(STT, "jarvis.voice.stt", VOICE,
                    text("Chi trascrive la tua voce", "Who transcribes your voice"),
                    text("Chi trasforma in testo quello che dici.", "Who turns what you say into text."), Kind.CHOICE,
                    List.of(choice("local", text("Sul tuo PC", "On your PC"),
                                    text("Gratis e privato.", "Free and private.")),
                            choice("groq", text("Groq", "Groq"),
                                    text("Molto preciso e veloce, con un piano gratuito.", "Very accurate and fast, with a free plan.")),
                            choice("openai", text("OpenAI", "OpenAI"), text("Preciso, a pagamento.", "Accurate, paid.")),
                            choice("browser", text("Browser", "Browser"),
                                    text("Gratis, ma non nell'app desktop.", "Free, but not in the desktop app.")),
                            choice("off", text("Disattivata", "Off"),
                                    text("Solo messaggi scritti.", "Written messages only."))), null),
            new Definition("jarvis.voice.local.stt-model", "jarvis.voice.local.stt-model", VOICE,
                    text("Trascrizione sul tuo PC", "Transcription on your PC"),
                    text("Più precisa o più veloce.", "More accurate or faster."), Kind.CHOICE,
                    List.of(choice("small", text("Più precisa", "More accurate"),
                                    text("Capisce meglio, un po' più lenta.", "Understands better, a little slower.")),
                            choice("base", text("Più veloce", "Faster"),
                                    text("Più rapida, con più errori.", "Quicker, with more mistakes."))), null)
                    .shownWhen(when(STT, "local")),
            new Definition(TTS, "jarvis.voice.tts", VOICE,
                    text("Chi dà la voce a Jarvis", "Who gives Jarvis a voice"),
                    text("Come Jarvis legge le risposte.", "How Jarvis reads his answers."), Kind.CHOICE,
                    List.of(choice("local", text("Sul tuo PC", "On your PC"),
                                    text("Gratis e privato.", "Free and private.")),
                            choice("azure", text("Azure", "Azure"),
                                    text("Voci neurali di Microsoft, molto naturali.", "Microsoft's neural voices, very natural.")),
                            choice("openai", text("OpenAI", "OpenAI"),
                                    text("Molto naturale, a pagamento.", "Very natural, paid.")),
                            choice("browser", text("Browser", "Browser"),
                                    text("La voce del computer.", "The computer's voice.")),
                            choice("off", text("Disattivata", "Off"),
                                    text("Solo risposte scritte.", "Written answers only."))), null),
            // test "voice": each choice can be heard before saving (SettingsService.preview)
            new Definition("jarvis.voice.azure.voices.it", "jarvis.voice.azure.voices.it", VOICE,
                    text("Voce di Azure", "Azure voice"),
                    text("Premi ▶ per ascoltarla.", "Press ▶ to hear it."), Kind.CHOICE,
                    List.of(choice("it-IT-DiegoNeural", text("Diego", "Diego"),
                                    text("Maschile, espressiva.", "Male, expressive.")),
                            choice("it-IT-GiuseppeMultilingualNeural", text("Giuseppe", "Giuseppe"),
                                    text("Maschile, calda.", "Male, warm.")),
                            choice("it-IT-AlessioMultilingualNeural", text("Alessio", "Alessio"),
                                    text("Maschile, giovane.", "Male, young.")),
                            choice("it-IT-IsabellaNeural", text("Isabella", "Isabella"),
                                    text("Femminile, espressiva.", "Female, expressive.")),
                            choice("it-IT-ElsaNeural", text("Elsa", "Elsa"), text("Femminile, pacata.", "Female, calm."))),
                    "voice").shownWhen(when(TTS, "azure"), when(LANGUAGE, "it")),
            new Definition("jarvis.voice.azure.voices.en", "jarvis.voice.azure.voices.en", VOICE,
                    text("Voce di Azure", "Azure voice"),
                    text("Premi ▶ per ascoltarla.", "Press ▶ to hear it."), Kind.CHOICE,
                    List.of(choice("en-US-AndrewMultilingualNeural", text("Andrew", "Andrew"),
                                    text("Maschile, americana.", "Male, American.")),
                            choice("en-GB-RyanNeural", text("Ryan", "Ryan"), text("Maschile, britannica.", "Male, British.")),
                            choice("en-US-AvaMultilingualNeural", text("Ava", "Ava"),
                                    text("Femminile, americana.", "Female, American.")),
                            choice("en-GB-SoniaNeural", text("Sonia", "Sonia"),
                                    text("Femminile, britannica.", "Female, British."))),
                    "voice").shownWhen(when(TTS, "azure"), when(LANGUAGE, "en")),
            new Definition("jarvis.voice.azure.rate", "jarvis.voice.azure.rate", VOICE,
                    text("Velocità della voce", "Voice speed"),
                    text("Anche l'anteprima ▶ la usa.", "The ▶ preview uses it too."), Kind.CHOICE,
                    List.of(choice("0", text("Normale", "Normal"), null),
                            choice("10", text("Un po' più veloce", "A little faster"), null),
                            choice("20", text("Più veloce", "Faster"), null),
                            choice("30", text("Veloce", "Fast"), null)), null).shownWhen(when(TTS, "azure")),
            // The keys in one tab, each only for a provider chosen in the other tabs
            new Definition("ANTHROPIC_API_KEY", "spring.ai.anthropic.api-key", KEYS,
                    text("Chiave API di Claude", "Claude API key"),
                    text("Da console.anthropic.com.", "From console.anthropic.com."),
                    Kind.SECRET, List.of(), "anthropic").shownWhen(when(PROVIDERS, "anthropic")).mandatory(),
            new Definition("spring.ai.anthropic.custom-headers.anthropic-workspace-id",
                    "spring.ai.anthropic.custom-headers.anthropic-workspace-id", KEYS,
                    text("Workspace di Claude", "Claude workspace"),
                    text("Solo per le chiavi di un'organizzazione: inizia con wrkspc_.",
                            "Only for an organization's keys: it starts with wrkspc_."),
                    Kind.TEXT, List.of(), null).shownWhen(when(PROVIDERS, "anthropic")),
            new Definition("OPENAI_API_KEY", "spring.ai.openai.api-key", KEYS,
                    text("Chiave API di OpenAI", "OpenAI API key"),
                    text("Da platform.openai.com; serve credito.", "From platform.openai.com; it needs credit."),
                    Kind.SECRET, List.of(), "openai")
                    .shownWhen(new Condition(List.of(PROVIDERS, STT, TTS), List.of("openai"))).mandatory(),
            new Definition("jarvis.voice.groq.api-key", "jarvis.voice.groq.api-key", KEYS,
                    text("Chiave API di Groq", "Groq API key"),
                    text("Da console.groq.com.", "From console.groq.com."),
                    Kind.SECRET, List.of(), "groq").shownWhen(when(STT, "groq")).mandatory(),
            new Definition("jarvis.voice.azure.key", "jarvis.voice.azure.key", KEYS,
                    text("Chiave API di Azure", "Azure API key"),
                    text("Dal portale di Azure: risorsa Speech, «Chiavi ed endpoint».",
                            "From the Azure portal: Speech resource, “Keys and Endpoint”."),
                    Kind.SECRET, List.of(), "azure").shownWhen(when(TTS, "azure")).mandatory(),
            new Definition("jarvis.voice.azure.region", "jarvis.voice.azure.region", KEYS,
                    text("Regione di Azure", "Azure region"),
                    text("Per esempio italynorth o westeurope.", "For example italynorth or westeurope."),
                    Kind.TEXT, List.of(), null).shownWhen(when(TTS, "azure")).mandatory(),
            new Definition("GITHUB_TOKEN", "jarvis.mcp.servers.github.token", KEYS, text("Token di GitHub", "GitHub token"),
                    text("Facoltativo, da github.com: un token che legge i repository e scrive issue e pull request.",
                            "Optional, from github.com: a token that reads repositories and writes issues and pull requests."),
                    Kind.SECRET, List.of(), "github"),
            new Definition(MAIL, MAIL, MAILBOX, text("Casella di posta", "Mailbox"),
                    text("Jarvis legge posta e calendario; invia solo dopo la tua conferma.",
                            "Jarvis reads mail and calendar; he sends only after you confirm."), Kind.CHOICE,
                    List.of(choice("off", text("Nessuna", "None"),
                                    text("Jarvis non legge la posta.", "Jarvis does not read your mail.")),
                            choice("outlook", text("Outlook.com o Hotmail", "Outlook.com or Hotmail"),
                                    text("Account Microsoft personale.", "Personal Microsoft account.")),
                            choice("microsoft365", text("Microsoft 365 di lavoro o scuola", "Microsoft 365 work or school"),
                                    text("Account di lavoro o di scuola.", "Work or school account.")),
                            choice("imap", text("Altra casella (IMAP)", "Other mailbox (IMAP)"),
                                    text("Gmail, iCloud, Libero…: solo posta.", "Gmail, iCloud, Yahoo…: mail only."))), null),
            new Definition("jarvis.mail.address", "jarvis.mail.address", MAILBOX, text("Indirizzo email", "Email address"),
                    text("L'indirizzo della casella.", "The address of the mailbox."), Kind.TEXT, List.of(), null)
                    .shownWhen(when(MAIL, "imap")).mandatory(),
            new Definition("MAIL_PASSWORD", "jarvis.mail.password", MAILBOX, text("Password per le app", "App password"),
                    text("Non quella dell'account: una password per le app, dalle impostazioni di sicurezza della casella.",
                            "Not the account's: an app password, from the mailbox's security settings."),
                    Kind.SECRET, List.of(), "imap").shownWhen(when(MAIL, "imap")).mandatory(),
            new Definition("jarvis.mail.imap-server", "jarvis.mail.imap-server", MAILBOX, text("Server IMAP", "IMAP server"),
                    text("Vuoto per le caselle più note.", "Empty for the best-known mailboxes."),
                    Kind.TEXT, List.of(), null).shownWhen(when(MAIL, "imap")),
            new Definition("jarvis.mail.smtp-server", "jarvis.mail.smtp-server", MAILBOX, text("Server SMTP", "SMTP server"),
                    text("Vuoto per le caselle più note.", "Empty for the best-known mailboxes."),
                    Kind.TEXT, List.of(), null).shownWhen(when(MAIL, "imap")),
            new Definition("jarvis.documents.folders", "jarvis.documents.folders", FOLDERS,
                    text("Cartelle dei documenti", "Document folders"),
                    text("Una per riga: Jarvis le può solo leggere.", "One per line: Jarvis can only read them."),
                    Kind.LIST, List.of(), null),
            new Definition("jarvis.agents.coder.workspaces", "jarvis.agents.coder.workspaces", FOLDERS,
                    text("Cartelle dei repository", "Repository folders"),
                    text("Una per riga: il Programmatore lavora solo qui.", "One per line: the Programmer works only here."),
                    Kind.LIST, List.of(), null),
            new Definition(APPS, APPS, APP_LIST, text("App che Jarvis può avviare", "Apps Jarvis can start"),
                    text("Sempre dopo la tua conferma. Negli argomenti, {target} è il file o la cartella da aprire.",
                            "Always after you confirm. In the arguments, {target} is the file or folder to open."),
                    Kind.APPS, List.of(), null),
            new Definition(ROUTINES, ROUTINES, ROUTINE_LIST, text("Routine", "Routines"),
                    text("Lavori che Jarvis fa da solo all'ora scelta, come un briefing del mattino. Puoi chiederle anche "
                                    + "a voce.",
                            "Work Jarvis does by himself at the chosen time, such as a morning briefing. You can also "
                                    + "ask for them by voice."),
                    Kind.ROUTINES, List.of(), null),
            new Definition(AGENTS, AGENTS, EXTERNAL, text("Agenti A2A", "A2A agents"),
                    text("Agenti di altri servizi, con il protocollo A2A: Jarvis può affidare loro dei lavori. Basta il loro "
                                    + "indirizzo e, se lo chiedono, un token.",
                            "Agents of other services, through the A2A protocol: Jarvis can give them work. Their "
                                    + "address is enough, and a token if they ask for one."),
                    Kind.AGENTS, List.of(), null));

    /**
     * Values in use until the user saves a setting: the lowest property source (SettingsDefaultsSource), so that
     * application.yml holds none of them. The keys are placeholders that let Spring AI start before the wizard.
     */
    public static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(LANGUAGE, language(Locale.getDefault()));
        defaults.put("jarvis.ui.theme", "auto");
        defaults.put("jarvis.providers.enabled", "anthropic");
        defaults.put("jarvis.providers.default-provider", "anthropic");
        defaults.put("spring.ai.anthropic.api-key", UNSET);
        defaults.put("spring.ai.openai.api-key", UNSET);
        defaults.put("spring.ai.ollama.base-url", "http://localhost:11434");
        defaults.put("jarvis.voice.stt", "local");
        defaults.put("jarvis.voice.tts", "local");
        defaults.put("jarvis.voice.local.stt-model", "small");
        defaults.put("jarvis.voice.azure.voices.it", "it-IT-DiegoNeural");
        defaults.put("jarvis.voice.azure.voices.en", "en-US-AndrewMultilingualNeural");
        defaults.put("jarvis.documents.folders[0]", System.getProperty("user.home") + "/.jarvis/documents");
        return defaults;
    }

    private SettingsCatalog() {
    }

    private static Choice choice(String value, Text label, Text description) {
        return new Choice(value, label, description);
    }

    /** The system's language if Jarvis speaks it, otherwise English. */
    static String language(Locale system) {
        return LANGUAGES.contains(system.getLanguage()) ? system.getLanguage() : "en";
    }

    /** Every configuration key a setting feeds: its property, and others that must agree with it. */
    public static List<String> properties(Definition definition) {
        return definition.name().equals("OPENAI_API_KEY")
                ? List.of(definition.property(), "jarvis.voice.openai.api-key") // the same key for chat and voice
                : List.of(definition.property());
    }

    public static Optional<Definition> find(String name) {
        return ALL.stream().filter(definition -> definition.name().equals(name)).findFirst();
    }
}
