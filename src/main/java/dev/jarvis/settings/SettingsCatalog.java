package dev.jarvis.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The settings the wizard and the Settings section show, in order. Pure data.
 *
 * <p>{@code name} is the key saved in the database; {@code property} is the configuration key the value
 * feeds (DatabaseSettingsSource publishes it under both). Older names such as ANTHROPIC_API_KEY stay as they
 * are: secrets are encrypted with their name as authenticated data, so a row cannot be renamed.
 */
public final class SettingsCatalog {

    /** APPS: the programs Jarvis may start, saved as JSON (see SettingsRows.expandApps). */
    public enum Kind { TEXT, SECRET, LIST, CHOICE, MULTI_CHOICE, APPS }

    /**
     * @param group tab of the Settings section and step of the wizard, in order of first appearance
     * @param test  which check the "Verifica" button runs (anthropic, openai, github), or null
     */
    public record Definition(String name, String property, String group, String label, String help, Kind kind,
                             List<Choice> choices, String test) {

        public List<String> values() {
            return choices.stream().map(Choice::value).toList();
        }
    }

    /** An option of a choice: the saved value, and what the user reads. */
    public record Choice(String value, String label, String description) {
    }

    public static final String MAIL = "JARVIS_MAIL";
    public static final String APPS = "jarvis.desktop.apps";
    /** A key not saved yet: lets Spring AI start; the Settings show it as not set. */
    public static final String UNSET = "not-configured";

    public static final List<Definition> ALL = List.of(
            new Definition("jarvis.agent.user-name", "jarvis.agent.user-name", "Profilo", "Il tuo nome",
                    "Jarvis ti chiama così.", Kind.TEXT, List.of(), null),
            new Definition("JARVIS_PROVIDERS", "jarvis.providers.enabled", "Modelli", "Fornitori da usare",
                    "Claude è il principale; gli altri sono facoltativi.", Kind.MULTI_CHOICE,
                    List.of(new Choice("anthropic", "Claude", "Anthropic: il modello principale di Jarvis."),
                            new Choice("openai", "OpenAI", "Modelli GPT, con una chiave a pagamento."),
                            new Choice("ollama", "Ollama", "Modelli locali sul tuo PC, se hai Ollama installato.")), null),
            new Definition("jarvis.providers.default-provider", "jarvis.providers.default-provider", "Modelli",
                    "Fornitore predefinito", "Quello scelto all'apertura di Jarvis; si cambia anche dall'header.",
                    Kind.CHOICE, List.of(new Choice("anthropic", "Claude", null), new Choice("openai", "OpenAI", null),
                            new Choice("ollama", "Ollama", null)), null),
            new Definition("ANTHROPIC_API_KEY", "spring.ai.anthropic.api-key", "Modelli", "Chiave API di Claude",
                    "Da console.anthropic.com, sezione API keys.", Kind.SECRET, List.of(), "anthropic"),
            new Definition("spring.ai.anthropic.custom-headers.anthropic-workspace-id",
                    "spring.ai.anthropic.custom-headers.anthropic-workspace-id", "Modelli", "Workspace di Claude",
                    "Solo per chiavi con ambito Organizzazione: l'id che inizia con wrkspc_.", Kind.TEXT, List.of(), null),
            new Definition("OPENAI_API_KEY", "spring.ai.openai.api-key", "Modelli", "Chiave API di OpenAI",
                    "Serve per i modelli OpenAI e per la voce OpenAI.", Kind.SECRET, List.of(), "openai"),
            new Definition("OLLAMA_BASE_URL", "spring.ai.ollama.base-url", "Modelli", "Indirizzo di Ollama",
                    "Solo se usi modelli locali con Ollama.", Kind.TEXT, List.of(), null),
            new Definition("JARVIS_VOICE_STT", "jarvis.voice.stt", "Voce", "Chi trascrive la tua voce",
                    "Chi trasforma in testo quello che dici.", Kind.CHOICE,
                    List.of(new Choice("local", "Sul tuo PC", "Whisper: gratis e privato, l'audio non esce dal computer."),
                            new Choice("openai", "OpenAI", "Più preciso, a pagamento: serve la chiave di OpenAI."),
                            new Choice("browser", "Browser", "Gratis, ma l'audio va a Google o Microsoft. Non "
                                    + "funziona nell'app desktop."),
                            new Choice("off", "Disattivata", "Solo messaggi scritti.")), null),
            new Definition("jarvis.voice.local.stt-model", "jarvis.voice.local.stt-model", "Voce",
                    "Trascrizione sul tuo PC", "Vale quando la trascrizione è «Sul tuo PC».", Kind.CHOICE,
                    List.of(new Choice("small", "Più precisa", "Whisper small: capisce molto meglio, qualche secondo "
                                    + "in più per frase."),
                            new Choice("base", "Più veloce", "Whisper base: circa un secondo per frase, più errori.")),
                    null),
            new Definition("JARVIS_VOICE_TTS", "jarvis.voice.tts", "Voce", "Chi dà la voce a Jarvis",
                    "Come Jarvis legge le risposte ad alta voce.", Kind.CHOICE,
                    List.of(new Choice("local", "Sul tuo PC", "Piper: gratis e privato."),
                            new Choice("openai", "OpenAI", "Più naturale, a pagamento: serve la chiave di OpenAI."),
                            new Choice("browser", "Browser", "La voce di sistema del computer."),
                            new Choice("off", "Disattivata", "Jarvis risponde solo per iscritto.")), null),
            new Definition(MAIL, MAIL, "Posta e calendario", "Casella di posta",
                    "Jarvis legge posta e calendario e, dopo la tua conferma, invia email e crea eventi.",
                    Kind.CHOICE, List.of(new Choice("off", "Nessuna", "Jarvis non accede alla posta."),
                            new Choice("outlook", "Outlook.com o Hotmail",
                                    "Account Microsoft personale: posta e calendario, accesso con un codice."),
                            new Choice("microsoft365", "Microsoft 365 di lavoro o scuola",
                                    "Posta e calendario; potrebbe servire il consenso dell'amministratore."),
                            new Choice("imap", "Altra casella (IMAP)",
                                    "Gmail, iCloud, Yahoo, Libero...: con una password per le app, solo posta.")), null),
            new Definition("jarvis.mail.address", "jarvis.mail.address", "Posta e calendario", "Indirizzo email",
                    "L'indirizzo della casella.", Kind.TEXT, List.of(), null),
            new Definition("MAIL_PASSWORD", "jarvis.mail.password", "Posta e calendario", "Password per le app",
                    "Non la password dell'account: una password per le app generata nelle impostazioni di sicurezza del "
                            + "provider (Gmail e iCloud richiedono la verifica in due passaggi).", Kind.SECRET, List.of(), "imap"),
            new Definition("jarvis.mail.imap-server", "jarvis.mail.imap-server", "Posta e calendario", "Server IMAP",
                    "Vuoto per Gmail, iCloud, Yahoo, Libero, Fastmail e GMX; altrimenti host oppure host:porta.",
                    Kind.TEXT, List.of(), null),
            new Definition("jarvis.mail.smtp-server", "jarvis.mail.smtp-server", "Posta e calendario", "Server SMTP",
                    "Come sopra: vuoto per i provider noti, altrimenti host oppure host:porta.", Kind.TEXT, List.of(), null),
            new Definition("GITHUB_TOKEN", "jarvis.mcp.servers.github.token", "GitHub", "Token di GitHub",
                    "Fine-grained token: Contents in lettura, Issues e Pull requests in lettura e scrittura.",
                    Kind.SECRET, List.of(), "github"),
            new Definition("jarvis.documents.folders", "jarvis.documents.folders", "Cartelle",
                    "Cartelle dei documenti", "Una per riga. Lo specialista Documenti le può solo leggere.",
                    Kind.LIST, List.of(), null),
            new Definition("jarvis.agents.coder.workspaces", "jarvis.agents.coder.workspaces", "Cartelle",
                    "Cartelle dei repository", "Una per riga. Il Programmatore lavora solo sui repository git qui dentro.",
                    Kind.LIST, List.of(), null),
            new Definition(APPS, APPS, "App", "App che Jarvis può avviare",
                    "Sempre dopo la tua conferma. Negli argomenti {target} è il file o la cartella che Jarvis passa "
                            + "all'app; argomenti separati da spazi. Link e cartelle si aprono anche senza elenco.",
                    Kind.APPS, List.of(), null));

    /**
     * Values in use until the user saves a setting: the lowest property source (SettingsDefaultsSource), so that
     * application.yml holds none of them. The keys are placeholders that let Spring AI start before the wizard.
     */
    public static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
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
