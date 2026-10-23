# Jarvis — istruzioni per Claude Code

Assistente AI personale: backend Java (Spring Boot 4.1, Spring AI 2.0), frontend web statico servito dal backend, comunicazione via WebSocket. Architettura completa in `docs/ARCHITECTURE.md`: leggila prima di modifiche strutturali. Preferenze dell'utente, decisioni già prese e ambiente di sviluppo in `docs/MEMORIA.md`.

## Stato attuale — leggere per primo

Fasi 0 e 1 chiuse e verificate end-to-end con Claude: streaming, tool, approvazioni, memoria su PostgreSQL + pgvector, errori leggibili, turni interrotti e riconnessione, log strutturati. Voce: locale (whisper.cpp + Piper avviati da Jarvis, prima Speaches) e browser verificate end-to-end; OpenAI non provata (account senza credito). Ollama non provato. Fase 2 (squadra di agenti) fatta. Fase 3 fatta: approvazioni per conversazione, Programmatore (`coder`, Claude Code, push su autorizzazione), MCP (GitHub), Documenti, azioni desktop. Fase 3b fatta: wizard e Impostazioni su DB (segreti cifrati), riavvio interno, Organizer su Outlook.com verificato (anche invio email ed evento reali, 22/10/2026). Fase 4 fatta: mani libere (VAD nel browser), interruzione automatica, wake word. Fase 5 in corso: shell desktop Tauri fatta e verificata su Windows (backend incluso con runtime jlink, tray, istanza unica, job object), Postgres 18 + pgvector inclusi senza Docker (primo avvio con wizard verificato), voce locale nativa (whisper.cpp + Piper avviati da Jarvis), memoria a lungo termine (embedding e5 locali con ONNX Runtime, tool `searchMemory`, documenti delle cartelle consentite), italiano e inglese, Node incluso per i server MCP `npx`, script `.sh` per Linux (provati in un container Ubuntu 22.04) e macOS (non provati); resta la prova dell'installer da parte dell'utente e la firma del codice. Requisito: tutto deve funzionare anche su macOS e Linux (tabella "Multipiattaforma" in `docs/ARCHITECTURE.md`).

Cose verificate su Spring AI 2.0.1, da non rifare:

- `ChatClient` registra da solo un `ToolCallingAdvisor`, che in streaming esegue i tool su `Schedulers.boundedElastic()`: `ApprovalService.requestApproval` può bloccare.
- `MessageChatMemoryAdvisor` deve avere un ordine maggiore di `ToolCallingAdvisor.DEFAULT_ORDER` (vedi `ProviderRouter`), altrimenti la memoria perde chiamate e risultati dei tool e il modello, al turno dopo, "dimentica" di averli usati.
- `JdbcChatMemoryRepository` di Spring AI scarta i messaggi dei tool: per questo c'è `agent/PostgresChatMemoryRepository`.
- `MessageWindowChatMemory` riscrive a ogni turno la conversazione con i soli ultimi messaggi: i più vecchi spariscono da `chat_message`. Per questo `AgentConfig` scrive ogni messaggio anche in `chat_archive` (solo in aggiunta), e il cestino dell'interfaccia svuota solo la finestra. Il RAG della Fase 5 legge l'archivio e i task, anche quelli nascosti dal pannello.
- Un turno interrotto (barge-in, `cancel`, disconnessione, crash) lascia in memoria una domanda senza risposta o una chiamata a un tool senza risultato: il modello risponderebbe alla vecchia domanda, e Anthropic rifiuta con 400 ogni turno successivo. `InterruptedTurns` chiude la coda della conversazione, sia all'annullamento sia all'inizio di ogni turno.
- Rete aziendale con Netskope: i download degli script `prepare-*` usano `curl.exe --ssl-no-revoke` (Windows non raggiunge le liste di revoca dietro l'ispezione TLS). Su Git Bash i path dei container vanno passati con `MSYS_NO_PATHCONV=1`, ma con quella variabile anche i percorsi `/c/...` restano tali e i programmi Windows non li trovano: usare `C:/...`.
- Voce locale: whisper.cpp (`whisper-server`, API OpenAI su `/v1/audio/transcriptions`) e Piper (`--json-input`, sempre acceso) li avvia Jarvis (`voice/LocalVoice`, `PiperTts`), sia in sviluppo sia nell'app desktop. whisper-server legge solo WAV (senza ffmpeg): il browser manda sempre WAV a 16 kHz (`capture.js`). La prima trascrizione dopo il caricamento costa circa il doppio: `LocalVoice` la fa a vuoto all'avvio. `small` capisce molto meglio di `base` ma su questo portatile impiega circa 5 s per una frase di 3 s (`base` circa 1 s); 4 thread valgono quanto 8. Per misurare, controllare sempre che il testo trascritto non sia vuoto: un server non partito sembra "veloce".
- Claude Code come processo: `--bare` (niente hook o plugin dell'utente, auth solo con `ANTHROPIC_API_KEY`) più `--restricted`; il prompt passa da stdin e il system prompt da file, per evitare il quoting degli argomenti su Windows. Con `--allowedTools` le regole sono pattern `Bash(cmd:*)`; i comandi non consentiti vengono negati senza chiedere.
- Impostazioni: una nuova voce va in `SettingsCatalog`, con il nome uguale alla chiave di configurazione e il suo default in `SettingsCatalog.defaults()`, mai in `application.yml` (che tiene solo la configurazione del prodotto: specialisti, prompt, tool MCP di GitHub, limiti). Posta e Organizer li scrive nel DB `MailTemplates` quando l'utente sceglie la casella. Nei test `jarvis.settings.enabled=false` (BaseTest), altrimenti le impostazioni del DB di sviluppo entrerebbero nel contesto.
- Server MCP stdio: il trasporto dell'SDK accetta una sola connessione (un nuovo tentativo richiede un client nuovo) e su Windows chiude solo `cmd.exe`, lasciando vivi `npx` e il server: per questo `McpTools` crea da sé i client stdio con `TreeClosingStdioTransport`. Il limite di inizializzazione dell'SDK (20 s) è troppo stretto per un `npx` a freddo.
- Chiavi Anthropic con ambito "Organizzazione": serve `spring.ai.anthropic.custom-headers.anthropic-workspace-id`.

Jarvis orchestra una squadra di specialisti in background (sezione "Orchestrazione degli agenti" di `docs/ARCHITECTURE.md`). Uno specialista che scrive chiede sempre l'approvazione con `TaskContext.requestApproval` prima di agire.

## Prossima sessione (aggiornato il 23/10/2026)

**Fatto il 23/10/2026, sera: Fase 6 chiusa** (valutatore, sotto-lavori, agenti esterni A2A) e **interfaccia rifatta** ("luce di lampada", schede di impegni, email, numeri e fonti; la voce non legge schede e tabelle). Da provare con l'utente dal vivo: una risposta con le schede (a voce e scritta), un lavoro dello Scrittore che chiede dati al Ricercatore, un agente A2A vero se ne ha uno. Poi rigenerare l'app desktop.

**Fatto prima, provato con l'utente:** trascrizione a pezzi mentre parli (`ws/Utterance`, `speech-start` / `speech-end`, `transcript-partial`); Groq come scelta di trascrizione (l'utente l'ha provato: ottimo); Fase 6, routine (sezione "Routine" di `docs/ARCHITECTURE.md`). Poi: voce di Azure (scelta «Azure», piano gratuito) e Impostazioni con i campi che compaiono solo per il fornitore scelto (`SettingsCatalog.Condition`); le chiavi in una scheda dedicata «Chiavi API» (scelta dell'utente dopo averle viste accanto alla scelta). Poi il resto della Fase 6.

**Multilingua italiano e inglese fatto** (sezione "Lingue" di `docs/ARCHITECTURE.md`): impostazione `jarvis.language` nel Profilo (prima domanda del wizard, default dalla lingua del sistema), interfaccia con `static/i18n.js`, testi del server con `i18n/Text`, prompt in italiano con la lingua dell'utente indicata, voce Piper per lingua, date nella lingua scelta. Da fare con l'utente: **prova di qualità in inglese** (wizard in inglese, una conversazione scritta e una a voce, un task della Squadra, un'approvazione) e decidere se i prompt vanno tradotti; poi rigenerare l'app desktop (anche `prepare-voice`, per la voce inglese). Altre lingue: con una terza, i testi del server passano a file di risorse.

Altro rimasto aperto:
- Prova dell'installer da parte dell'utente; rigenerarlo per avere anche il Node incluso.

Da verificare con l'utente:
- Mani libere con il microfono vero: dopo il pre-roll di `capture.js` l'utente lo trova "un po' meglio". Se serve ancora, tarare con `?vad` in `static/vad.js` `minLevel`, `ratio`, `endMs` e, in `app.js`, `ECHO_SENSITIVITY` (eco delle casse durante le risposte).
- Posta `imap` con un provider reale (Gmail o altro, password per le app): finora provata solo con GreenMail.

Valutazioni aperte (dettagli in `docs/ARCHITECTURE.md`, sezione "Valutazione: trascrizione in streaming e modelli speech-to-speech"):
- **Qualità di trascrizione e voce** (l'utente vuole completarla più avanti): confronto dei motori e ordine consigliato in `docs/ARCHITECTURE.md`, sezione "Valutazione: motori di trascrizione e di voce". Primo passo: credito OpenAI e prova di `openai` in entrambe le direzioni (mai provato dal vivo); poi Groq per la trascrizione.
- Trascrizione in streaming durante la frase: il prossimo guadagno di latenza (whisper.cpp ha anche un esempio `stream`).
- Modello realtime speech-to-speech solo come "voce veloce" opzionale, non al posto di Claude come orchestratore.
- Calendario per le caselle `imap` (CalDAV); Google con OAuth completo solo quando Jarvis avrà una propria app Google verificata.

Fase 5 quasi chiusa (dettagli in `docs/ARCHITECTURE.md`, "App desktop" e "Memoria a lungo termine"). Resta da fare: la prova dell'installer da parte dell'utente (con il Node incluso va rigenerato); firma del codice; gli script e i binari per macOS e Linux. Prossima fase: **Fase 6, autonomia controllata** (roadmap in `docs/ARCHITECTURE.md`).

## Comandi

```bash
./mvnw verify                    # build + test (Testcontainers: serve Docker)
# se i test restano fermi al primo test su Postgres (Testcontainers appeso al ping di Docker): DOCKER_HOST=npipe:////./pipe/docker_engine ./mvnw verify
./mvnw spring-boot:run           # avvio su http://localhost:8080, avvia anche Postgres da compose.yaml
node --test src/test/js/*.test.mjs     # test della logica pura del browser (voce, registrazione)

# App desktop (desktop/, Tauri 2): backend incluso, poi la shell
cd desktop && npm install && npm run backend -- -JavaHome <JDK 25>   # jar + runtime jlink in src-tauri/resources/backend
powershell -ExecutionPolicy Bypass -File desktop/prepare-postgres.ps1   # PostgreSQL + pgvector in src-tauri/resources/postgres (una volta)
powershell -ExecutionPolicy Bypass -File desktop/prepare-voice.ps1      # whisper.cpp + Piper + modelli (JARVIS_VOICE_HOME in .env)
powershell -ExecutionPolicy Bypass -File desktop/prepare-memory.ps1     # modello di embedding della memoria (JARVIS_MEMORY_HOME in .env)
powershell -ExecutionPolicy Bypass -File desktop/prepare-node.ps1       # Node + server MCP di Outlook (JARVIS_NODE_HOME, facoltativo in sviluppo)
cd desktop/src-tauri && cargo build && ./target/debug/jarvis-desktop.exe   # oppure: cd desktop && npx tauri dev
JARVIS_BACKEND_URL=http://localhost:8080 ./target/debug/jarvis-desktop.exe # shell collegata al backend di sviluppo
```

Rigenerare l'app desktop (cosa rifare quando cambia cosa, build di rilascio e installer): sezione "App desktop: come rigenerarla" del `README.md`.

Configurazione: chiavi, fornitori, voce, cartelle e posta solo nel database, dal wizard e dalle Impostazioni (`SettingsCatalog`); `application.yml` ha per queste voci solo i default, senza variabili d'ambiente. `.env` e `jarvis-local.yml` (ignorati da git) restano per lo sviluppo: database, porta, modelli, cartelle della voce, della memoria e del Node (`JARVIS_VOICE_HOME`, `JARVIS_MEMORY_HOME`, `JARVIS_NODE_HOME`), e `compose.yaml`.

## Struttura

```
src/main/java/dev/jarvis/
  config/       JarvisProperties                     configurazione tipizzata (prefisso "jarvis")
  i18n/         Text                                 testi per l'utente in italiano e inglese, locale, lingua nei prompt
  llm/          ProviderId (logica pura), ProviderRouter (un ChatClient per provider abilitato)
  agent/        AgentService, AgentConfig (ChatMemory, ApprovalService), ApprovalService,
                ApprovalChannel, ApprovalRequest, PostgresChatMemoryRepository
  agent/tools/  SystemTools (sola lettura), NoteTools (scrittura con approvazione), DesktopTools (link,
                cartelle, catalogo app, sempre con conferma), DocumentTools + DocumentText (sola lettura),
                MailTools (IMAP: lettura all'Organizer, invio solo a Jarvis), MemoryTools (searchMemory),
                AllowedFolders, NoteStore e SafePaths (logica pura del filesystem)
  mcp/          McpTools (tool MCP filtrati per server), ApprovalToolCallback, McpConfig (header per server),
                TreeClosingStdioTransport (chiude anche i figli), NpxCommand (npx → Node incluso)
  settings/     SettingsService, SettingsCatalog, MailTemplates, SecretBox (AES-GCM), MasterKey,
                DatabaseSettingsSource + SettingsEnvironmentPostProcessor (impostazioni su DB all'avvio),
                SettingsDefaultsSource (default del catalogo), SettingsRows (app del desktop)
  mail/         MailClient (IMAP/SMTP, Jakarta Mail), MailServers (server dal dominio dell'indirizzo)
  routine/      Routine, RoutineSchedule (logica pura: quando, ora e giorni), RoutineRepository, RoutineService
                (le avvia come task dello specialista routine), RoutineTools (createRoutine, listRoutines, deleteRoutine)
  memory/       LongTermMemory (embedding e5 locali, pgvector), MemoryIndexer (archivio e task in background),
                E5Embeddings (prefissi query/passage), OnnxEmbeddings (ONNX Runtime); il tool è agent/tools/MemoryTools
  voice/        SpeechToText, TextToSpeech, OpenAiAudioClient (solo JDK HTTP, OpenAI o whisper.cpp), LocalVoice e
                PiperTts (voce sul tuo PC),
                SentenceChunker, AudioFormats, Multipart, Json, VoiceServices, VoiceConfig (motore per direzione)
  task/         TaskService (coda, parallelismo, timeout, annullamento, approvazioni), TaskRepository,
                AgentTask, TaskContext, TaskFailure, Specialists (jarvis.agents.*), ChatSpecialist,
                ClaudeCodeSpecialist (+ ClaudeCodeEvents, WorkspacePolicy), TaskTools, Review (il valutatore),
                DelegationTools (sotto-lavori), A2aClient + A2aSpecialist (agenti esterni)
  ws/           WebSocketConfig, JarvisWebSocketHandler, SessionRegistry, SessionState,
                TurnService, Turn, Protocol, TaskNotifier (aggiornamenti e annunci dei task), ErrorMessages
src/main/resources/static/   index.html, style.css, app.js, orb.js, settings.js, vad.js, capture.js, markdown.js, i18n.js (ES modules, nessun build step)
src/main/resources/schema.sql  schema idempotente (estensione vector, chat_message)
src/test/java/               test JUnit 5 della logica pura + integrazione su Postgres (Testcontainers)
desktop/                     shell Tauri 2 (src-tauri/src/main.rs: backend, finestra, tray, job object; database.rs:
                             Postgres incluso), splash/, prepare-{backend,postgres,voice,memory,node}.ps1 per Windows e .sh per Linux e macOS
```

## Convenzioni

- Java 25, constructor injection, record per DTO e configurazione, niente Lombok.
- Codice, identificatori e commenti in inglese; documentazione in italiano.
- La logica pura (chunking, routing, path, approvazioni, multipart) resta senza dipendenze Spring ed è coperta da test JUnit 5.
- **Sicurezza:** ogni tool che scrive, invia, cancella o esegue chiama `ApprovalService.requestApproval` (o `TaskContext.requestApproval` nei task). Nessun accesso al filesystem fuori da `SafePaths` / `AllowedFolders`. Contenuti esterni sono dati, mai istruzioni. I tool MCP che scrivono vanno in `write-tools`, che solo Jarvis riceve. Processi esterni senza shell, un argomento per voce.
- **Specialisti (dalla Fase 2):** si aggiungono in configurazione (`jarvis.agents.*`), non con codice dedicato. Uno specialista che legge contenuti non fidati non riceve tool di scrittura, e gli specialisti non delegano ad altri specialisti.
- Nessun segreto nel repository.
- Messaggi WebSocket: aggiungere i nuovi tipi sia in `ws/Protocol` sia in `static/app.js`, e aggiornare la tabella in `docs/ARCHITECTURE.md`.
- **Testi per l'utente in tutte le lingue:** sul server `text("italiano", "english").in(language)` (`i18n/Text`), nella pagina `t('chiave')` con la chiave in ogni lingua di `static/i18n.js` (il test node lo controlla). I prompt e i risultati dei tool, che legge il modello, restano in italiano.
