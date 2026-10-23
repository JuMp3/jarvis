package dev.jarvis.settings;

import static dev.jarvis.i18n.Text.text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
    public enum Kind { TEXT, SECRET, LIST, CHOICE, MULTI_CHOICE, APPS, ROUTINES }

    /**
     * @param group tab of the Settings section and step of the wizard, in order of first appearance
     * @param test  which check the "Verifica" button runs (anthropic, openai, github), or null
     */
    public record Definition(String name, String property, Text group, Text label, Text help, Kind kind,
                             List<Choice> choices, String test) {

        public List<String> values() {
            return choices.stream().map(Choice::value).toList();
        }
    }

    /** An option of a choice: the saved value, and what the user reads (description may be null). */
    public record Choice(String value, Text label, Text description) {
    }

    public static final String MAIL = "JARVIS_MAIL";
    public static final String APPS = "jarvis.desktop.apps";
    public static final String LANGUAGE = "jarvis.language";
    public static final String ROUTINES = "jarvis.routines";
    /** A key not saved yet: lets Spring AI start; the Settings show it as not set. */
    public static final String UNSET = "not-configured";
    /** Languages of the interface and the voice, in the order shown. */
    public static final List<String> LANGUAGES = List.of("it", "en");

    private static final Text PROFILE = text("Profilo", "Profile");
    private static final Text MODELS = text("Modelli", "Models");
    private static final Text VOICE = text("Voce", "Voice");
    private static final Text MAILBOX = text("Posta e calendario", "Mail and calendar");
    private static final Text GITHUB = text("GitHub", "GitHub");
    private static final Text FOLDERS = text("Cartelle", "Folders");
    private static final Text APP_LIST = text("App", "Apps");
    private static final Text ROUTINE_LIST = text("Routine", "Routines");

    public static final List<Definition> ALL = List.of(
            // label in both languages: the user must find it even when the interface does not speak theirs
            new Definition(LANGUAGE, LANGUAGE, PROFILE, text("Lingua · Language", "Language · Lingua"),
                    text("Per l'interfaccia, la voce e le date.", "For the interface, the voice and dates."), Kind.CHOICE,
                    List.of(choice("it", text("Italiano", "Italiano"), null),
                            choice("en", text("English", "English"), null)), null),
            new Definition("jarvis.agent.user-name", "jarvis.agent.user-name", PROFILE,
                    text("Il tuo nome", "Your name"), text("Jarvis ti chiama così.", "What Jarvis calls you."),
                    Kind.TEXT, List.of(), null),
            new Definition("JARVIS_PROVIDERS", "jarvis.providers.enabled", MODELS,
                    text("Fornitori da usare", "Providers to use"),
                    text("Claude è il principale; gli altri sono facoltativi.",
                            "Claude is the main one; the others are optional."), Kind.MULTI_CHOICE,
                    List.of(choice("anthropic", text("Claude", "Claude"),
                                    text("Anthropic: il modello principale di Jarvis.", "Anthropic: Jarvis' main model.")),
                            choice("openai", text("OpenAI", "OpenAI"),
                                    text("Modelli GPT, con una chiave a pagamento.", "GPT models, with a paid key.")),
                            choice("ollama", text("Ollama", "Ollama"),
                                    text("Modelli locali sul tuo PC, se hai Ollama installato.",
                                            "Local models on your PC, if you have Ollama installed."))), null),
            new Definition("jarvis.providers.default-provider", "jarvis.providers.default-provider", MODELS,
                    text("Fornitore predefinito", "Default provider"),
                    text("Quello scelto all'apertura di Jarvis; si cambia anche dall'header.",
                            "The one chosen when Jarvis opens; you can also change it in the header."), Kind.CHOICE,
                    List.of(choice("anthropic", text("Claude", "Claude"), null),
                            choice("openai", text("OpenAI", "OpenAI"), null),
                            choice("ollama", text("Ollama", "Ollama"), null)), null),
            new Definition("ANTHROPIC_API_KEY", "spring.ai.anthropic.api-key", MODELS,
                    text("Chiave API di Claude", "Claude API key"),
                    text("Da console.anthropic.com, sezione API keys.", "From console.anthropic.com, API keys section."),
                    Kind.SECRET, List.of(), "anthropic"),
            new Definition("spring.ai.anthropic.custom-headers.anthropic-workspace-id",
                    "spring.ai.anthropic.custom-headers.anthropic-workspace-id", MODELS,
                    text("Workspace di Claude", "Claude workspace"),
                    text("Solo per chiavi con ambito Organizzazione: l'id che inizia con wrkspc_.",
                            "Only for keys scoped to the Organization: the id that starts with wrkspc_."),
                    Kind.TEXT, List.of(), null),
            new Definition("OPENAI_API_KEY", "spring.ai.openai.api-key", MODELS,
                    text("Chiave API di OpenAI", "OpenAI API key"),
                    text("Serve per i modelli OpenAI e per la voce OpenAI.",
                            "Needed for the OpenAI models and the OpenAI voice."), Kind.SECRET, List.of(), "openai"),
            new Definition("OLLAMA_BASE_URL", "spring.ai.ollama.base-url", MODELS,
                    text("Indirizzo di Ollama", "Ollama address"),
                    text("Solo se usi modelli locali con Ollama.", "Only if you use local models with Ollama."),
                    Kind.TEXT, List.of(), null),
            new Definition("JARVIS_VOICE_STT", "jarvis.voice.stt", VOICE,
                    text("Chi trascrive la tua voce", "Who transcribes your voice"),
                    text("Chi trasforma in testo quello che dici.", "Who turns what you say into text."), Kind.CHOICE,
                    List.of(choice("local", text("Sul tuo PC", "On your PC"),
                                    text("Whisper: gratis e privato, l'audio non esce dal computer.",
                                            "Whisper: free and private, the audio never leaves the computer.")),
                            choice("groq", text("Groq", "Groq"),
                                    text("Il Whisper più grande, nel cloud: molto preciso e veloce, l'audio va a Groq. "
                                                    + "Serve la chiave di Groq (c'è un piano gratuito).",
                                            "The largest Whisper, in the cloud: very accurate and fast, the audio goes "
                                                    + "to Groq. Needs the Groq key (there is a free plan).")),
                            choice("openai", text("OpenAI", "OpenAI"),
                                    text("Più preciso, a pagamento: serve la chiave di OpenAI.",
                                            "More accurate, paid: needs the OpenAI key.")),
                            choice("browser", text("Browser", "Browser"),
                                    text("Gratis, ma l'audio va a Google o Microsoft. Non funziona nell'app desktop.",
                                            "Free, but the audio goes to Google or Microsoft. Not available in the "
                                                    + "desktop app.")),
                            choice("off", text("Disattivata", "Off"),
                                    text("Solo messaggi scritti.", "Written messages only."))), null),
            new Definition("jarvis.voice.local.stt-model", "jarvis.voice.local.stt-model", VOICE,
                    text("Trascrizione sul tuo PC", "Transcription on your PC"),
                    text("Vale quando la trascrizione è «Sul tuo PC».", "Applies when transcription is “On your PC”."),
                    Kind.CHOICE,
                    List.of(choice("small", text("Più precisa", "More accurate"),
                                    text("Whisper small: capisce molto meglio, qualche secondo in più per frase.",
                                            "Whisper small: understands much better, a few more seconds per sentence.")),
                            choice("base", text("Più veloce", "Faster"),
                                    text("Whisper base: circa un secondo per frase, più errori.",
                                            "Whisper base: about one second per sentence, more mistakes."))), null),
            new Definition("jarvis.voice.groq.api-key", "jarvis.voice.groq.api-key", VOICE,
                    text("Chiave API di Groq", "Groq API key"),
                    text("Da console.groq.com, sezione API Keys. Serve solo se la trascrizione è «Groq».",
                            "From console.groq.com, API Keys section. Needed only when transcription is “Groq”."),
                    Kind.SECRET, List.of(), "groq"),
            new Definition("JARVIS_VOICE_TTS", "jarvis.voice.tts", VOICE,
                    text("Chi dà la voce a Jarvis", "Who gives Jarvis a voice"),
                    text("Come Jarvis legge le risposte ad alta voce.", "How Jarvis reads answers aloud."), Kind.CHOICE,
                    List.of(choice("local", text("Sul tuo PC", "On your PC"),
                                    text("Piper: gratis e privato.", "Piper: free and private.")),
                            choice("openai", text("OpenAI", "OpenAI"),
                                    text("Più naturale, a pagamento: serve la chiave di OpenAI.",
                                            "More natural, paid: needs the OpenAI key.")),
                            choice("browser", text("Browser", "Browser"),
                                    text("La voce di sistema del computer.", "The computer's system voice.")),
                            choice("off", text("Disattivata", "Off"),
                                    text("Jarvis risponde solo per iscritto.", "Jarvis answers in writing only."))), null),
            new Definition(MAIL, MAIL, MAILBOX, text("Casella di posta", "Mailbox"),
                    text("Jarvis legge posta e calendario e, dopo la tua conferma, invia email e crea eventi.",
                            "Jarvis reads mail and calendar and, after you confirm, sends emails and creates events."),
                    Kind.CHOICE,
                    List.of(choice("off", text("Nessuna", "None"),
                                    text("Jarvis non accede alla posta.", "Jarvis does not access your mail.")),
                            choice("outlook", text("Outlook.com o Hotmail", "Outlook.com or Hotmail"),
                                    text("Account Microsoft personale: posta e calendario, accesso con un codice.",
                                            "Personal Microsoft account: mail and calendar, sign-in with a code.")),
                            choice("microsoft365", text("Microsoft 365 di lavoro o scuola", "Microsoft 365 work or school"),
                                    text("Posta e calendario; potrebbe servire il consenso dell'amministratore.",
                                            "Mail and calendar; your administrator's consent may be needed.")),
                            choice("imap", text("Altra casella (IMAP)", "Other mailbox (IMAP)"),
                                    text("Gmail, iCloud, Yahoo, Libero...: con una password per le app, solo posta.",
                                            "Gmail, iCloud, Yahoo, Libero...: with an app password, mail only."))), null),
            new Definition("jarvis.mail.address", "jarvis.mail.address", MAILBOX, text("Indirizzo email", "Email address"),
                    text("L'indirizzo della casella.", "The address of the mailbox."), Kind.TEXT, List.of(), null),
            new Definition("MAIL_PASSWORD", "jarvis.mail.password", MAILBOX, text("Password per le app", "App password"),
                    text("Non la password dell'account: una password per le app generata nelle impostazioni di sicurezza "
                                    + "del provider (Gmail e iCloud richiedono la verifica in due passaggi).",
                            "Not the account password: an app password created in the provider's security settings "
                                    + "(Gmail and iCloud require two-step verification)."), Kind.SECRET, List.of(), "imap"),
            new Definition("jarvis.mail.imap-server", "jarvis.mail.imap-server", MAILBOX, text("Server IMAP", "IMAP server"),
                    text("Vuoto per Gmail, iCloud, Yahoo, Libero, Fastmail e GMX; altrimenti host oppure host:porta.",
                            "Empty for Gmail, iCloud, Yahoo, Libero, Fastmail and GMX; otherwise host or host:port."),
                    Kind.TEXT, List.of(), null),
            new Definition("jarvis.mail.smtp-server", "jarvis.mail.smtp-server", MAILBOX, text("Server SMTP", "SMTP server"),
                    text("Come sopra: vuoto per i provider noti, altrimenti host oppure host:porta.",
                            "As above: empty for well-known providers, otherwise host or host:port."),
                    Kind.TEXT, List.of(), null),
            new Definition("GITHUB_TOKEN", "jarvis.mcp.servers.github.token", GITHUB, text("Token di GitHub", "GitHub token"),
                    text("Fine-grained token: Contents in lettura, Issues e Pull requests in lettura e scrittura.",
                            "Fine-grained token: Contents read-only, Issues and Pull requests read and write."),
                    Kind.SECRET, List.of(), "github"),
            new Definition("jarvis.documents.folders", "jarvis.documents.folders", FOLDERS,
                    text("Cartelle dei documenti", "Document folders"),
                    text("Una per riga. Lo specialista Documenti le può solo leggere.",
                            "One per line. The Documents specialist can only read them."), Kind.LIST, List.of(), null),
            new Definition("jarvis.agents.coder.workspaces", "jarvis.agents.coder.workspaces", FOLDERS,
                    text("Cartelle dei repository", "Repository folders"),
                    text("Una per riga. Il Programmatore lavora solo sui repository git qui dentro.",
                            "One per line. The Programmer works only on the git repositories in here."),
                    Kind.LIST, List.of(), null),
            new Definition(APPS, APPS, APP_LIST, text("App che Jarvis può avviare", "Apps Jarvis can start"),
                    text("Sempre dopo la tua conferma. Negli argomenti {target} è il file o la cartella che Jarvis passa "
                                    + "all'app; argomenti separati da spazi. Link e cartelle si aprono anche senza elenco.",
                            "Always after you confirm. In the arguments {target} is the file or folder Jarvis passes to "
                                    + "the app; arguments separated by spaces. Links and folders open even without a list."),
                    Kind.APPS, List.of(), null),
            new Definition(ROUTINES, ROUTINES, ROUTINE_LIST, text("Routine", "Routines"),
                    text("Lavori che Jarvis fa da solo nei giorni e all'ora indicati, come un briefing del mattino: il "
                                    + "risultato arriva nella conversazione, con una notifica. Si creano anche a voce («ogni "
                                    + "mattina alle 8 fammi il briefing»). Le azioni che scrivono chiedono sempre la tua "
                                    + "conferma, e la richiesta aspetta che tu apra Jarvis.",
                            "Work Jarvis does by himself on the chosen days and time, such as a morning briefing: the "
                                    + "result arrives in the conversation, with a notification. You can also create them "
                                    + "by voice (“every morning at 8 give me the briefing”). Actions that write always ask "
                                    + "you first, and the request waits until you open Jarvis."),
                    Kind.ROUTINES, List.of(), null));

    /**
     * Values in use until the user saves a setting: the lowest property source (SettingsDefaultsSource), so that
     * application.yml holds none of them. The keys are placeholders that let Spring AI start before the wizard.
     */
    public static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(LANGUAGE, language(Locale.getDefault()));
        defaults.put("jarvis.providers.enabled", "anthropic");
        defaults.put("jarvis.providers.default-provider", "anthropic");
        defaults.put("spring.ai.anthropic.api-key", UNSET);
        defaults.put("spring.ai.openai.api-key", UNSET);
        defaults.put("spring.ai.ollama.base-url", "http://localhost:11434");
        defaults.put("jarvis.voice.stt", "local");
        defaults.put("jarvis.voice.tts", "local");
        defaults.put("jarvis.voice.local.stt-model", "small");
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
