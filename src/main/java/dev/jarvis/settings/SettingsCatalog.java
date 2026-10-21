package dev.jarvis.settings;

import java.util.List;
import java.util.Optional;

/**
 * The settings the wizard and the Settings section show, in order. Pure data.
 *
 * <p>{@code name} is the key saved in the database; {@code property} is where Spring finds the effective
 * value, when the name is a placeholder of application.yml (e.g. ANTHROPIC_API_KEY).
 */
public final class SettingsCatalog {

    public enum Kind { TEXT, SECRET, LIST, CHOICE, MULTI_CHOICE }

    /** @param test which check the "Verifica" button runs (anthropic, openai, github), or null */
    public record Definition(String name, String property, String group, String label, String help, Kind kind,
                             List<String> choices, String test) {
    }

    public static final String MAIL = "JARVIS_MAIL";

    public static final List<Definition> ALL = List.of(
            new Definition("JARVIS_PROVIDERS", "jarvis.providers.enabled", "Modelli", "Fornitori da usare",
                    "Claude è il principale; OpenAI e Ollama (modelli locali) sono facoltativi.", Kind.MULTI_CHOICE,
                    List.of("anthropic", "openai", "ollama"), null),
            new Definition("ANTHROPIC_API_KEY", "ANTHROPIC_API_KEY", "Modelli", "Chiave API di Claude",
                    "Da console.anthropic.com, sezione API keys.", Kind.SECRET, List.of(), "anthropic"),
            new Definition("spring.ai.anthropic.custom-headers.anthropic-workspace-id",
                    "spring.ai.anthropic.custom-headers.anthropic-workspace-id", "Modelli", "Workspace di Claude",
                    "Solo per chiavi con ambito Organizzazione: l'id che inizia con wrkspc_.", Kind.TEXT, List.of(), null),
            new Definition("OPENAI_API_KEY", "OPENAI_API_KEY", "Modelli", "Chiave API di OpenAI",
                    "Serve per i modelli OpenAI e per la voce OpenAI.", Kind.SECRET, List.of(), "openai"),
            new Definition("OLLAMA_BASE_URL", "spring.ai.ollama.base-url", "Modelli", "Indirizzo di Ollama",
                    "Solo se usi modelli locali con Ollama.", Kind.TEXT, List.of(), null),
            new Definition("JARVIS_VOICE_STT", "jarvis.voice.stt", "Voce", "Chi trascrive la tua voce",
                    "local: sul tuo PC (Speaches). openai: più preciso, a pagamento. browser: gratis, l'audio va a "
                            + "Google o Microsoft.", Kind.CHOICE, List.of("local", "openai", "browser", "off"), null),
            new Definition("JARVIS_VOICE_TTS", "jarvis.voice.tts", "Voce", "Chi dà la voce a Jarvis",
                    "local: Piper sul tuo PC. openai: più naturale, a pagamento. browser: la voce di sistema.",
                    Kind.CHOICE, List.of("local", "openai", "browser", "off"), null),
            new Definition("jarvis.documents.folders", "jarvis.documents.folders", "Cartelle",
                    "Cartelle dei documenti", "Una per riga. Lo specialista Documenti le può solo leggere.",
                    Kind.LIST, List.of(), null),
            new Definition("jarvis.agents.coder.workspaces", "jarvis.agents.coder.workspaces", "Cartelle",
                    "Cartelle dei repository", "Una per riga. Il Programmatore lavora solo sui repository git qui dentro.",
                    Kind.LIST, List.of(), null),
            new Definition("GITHUB_TOKEN", "GITHUB_TOKEN", "Account", "Token di GitHub",
                    "Fine-grained token: Contents in lettura, Issues e Pull requests in lettura e scrittura.",
                    Kind.SECRET, List.of(), "github"),
            new Definition(MAIL, MAIL, "Account", "Posta e calendario",
                    "outlook: Outlook.com o Hotmail personale. microsoft365: account di lavoro o scuola (potrebbe servire "
                            + "il consenso dell'amministratore). Per entrambi accesso Microsoft con un codice. imap: qualsiasi "
                            + "altra casella (Gmail, iCloud, Yahoo, Libero...) con una password per le app, solo posta.",
                    Kind.CHOICE, List.of("off", "outlook", "microsoft365", "imap"), null),
            new Definition("jarvis.mail.address", "jarvis.mail.address", "Account", "Indirizzo email (imap)",
                    "Solo per imap.", Kind.TEXT, List.of(), null),
            new Definition("MAIL_PASSWORD", "MAIL_PASSWORD", "Account", "Password per le app (imap)",
                    "Non la password dell'account: una password per le app generata nelle impostazioni di sicurezza del "
                            + "provider (Gmail e iCloud richiedono la verifica in due passaggi).", Kind.SECRET, List.of(), "imap"),
            new Definition("jarvis.mail.imap-server", "jarvis.mail.imap-server", "Account", "Server IMAP (imap)",
                    "Vuoto per Gmail, iCloud, Yahoo, Libero, Fastmail e GMX; altrimenti host oppure host:porta.",
                    Kind.TEXT, List.of(), null),
            new Definition("jarvis.mail.smtp-server", "jarvis.mail.smtp-server", "Account", "Server SMTP (imap)",
                    "Come sopra: vuoto per i provider noti, altrimenti host oppure host:porta.", Kind.TEXT, List.of(), null));

    private SettingsCatalog() {
    }

    public static Optional<Definition> find(String name) {
        return ALL.stream().filter(definition -> definition.name().equals(name)).findFirst();
    }
}
