# Jarvis — istruzioni per Claude Code

Assistente AI personale: backend Java (Spring Boot 4.1, Spring AI 2.0), frontend web statico servito dal backend, comunicazione via WebSocket. Architettura completa in `docs/ARCHITECTURE.md`: leggila prima di modifiche strutturali.

## Stato attuale — leggere per primo

Fasi 0 e 1 chiuse e verificate end-to-end con Claude: streaming, tool, approvazioni, memoria su PostgreSQL + pgvector, errori leggibili, turni interrotti e riconnessione, log strutturati. Voce: locale (Speaches: Whisper + Piper) e browser verificate end-to-end; OpenAI non provata (account senza credito). Ollama non provato. Fase 2 (squadra di agenti) fatta. Fase 3 fatta: approvazioni per conversazione, Programmatore (`coder`, Claude Code, push su autorizzazione), MCP (GitHub), Documenti, azioni desktop. Fase 3b fatta: wizard e Impostazioni su DB (segreti cifrati), riavvio interno, Organizer su Outlook.com verificato (anche invio email ed evento reali, 22/10/2026). Fase 4 fatta: mani libere (VAD nel browser), interruzione automatica, wake word. Prossima: Fase 5 (app desktop).

Cose verificate su Spring AI 2.0.1, da non rifare:

- `ChatClient` registra da solo un `ToolCallingAdvisor`, che in streaming esegue i tool su `Schedulers.boundedElastic()`: `ApprovalService.requestApproval` può bloccare.
- `MessageChatMemoryAdvisor` deve avere un ordine maggiore di `ToolCallingAdvisor.DEFAULT_ORDER` (vedi `ProviderRouter`), altrimenti la memoria perde chiamate e risultati dei tool e il modello, al turno dopo, "dimentica" di averli usati.
- `JdbcChatMemoryRepository` di Spring AI scarta i messaggi dei tool: per questo c'è `agent/PostgresChatMemoryRepository`.
- `MessageWindowChatMemory` riscrive a ogni turno la conversazione con i soli ultimi messaggi: i più vecchi spariscono da `chat_message`. Per questo `AgentConfig` scrive ogni messaggio anche in `chat_archive` (solo in aggiunta), e il cestino dell'interfaccia svuota solo la finestra. Il RAG della Fase 5 legge l'archivio e i task, anche quelli nascosti dal pannello.
- Un turno interrotto (barge-in, `cancel`, disconnessione, crash) lascia in memoria una domanda senza risposta o una chiamata a un tool senza risultato: il modello risponderebbe alla vecchia domanda, e Anthropic rifiuta con 400 ogni turno successivo. `InterruptedTurns` chiude la coda della conversazione, sia all'annullamento sia all'inizio di ogni turno.
- Rete aziendale con Netskope: Speaches scarica i modelli solo con `SPEACHES_CA_BUNDLE` (vedi README), e su Git Bash i path dei container vanno passati con `MSYS_NO_PATHCONV=1`.
- Claude Code come processo: `--bare` (niente hook o plugin dell'utente, auth solo con `ANTHROPIC_API_KEY`) più `--restricted`; il prompt passa da stdin e il system prompt da file, per evitare il quoting degli argomenti su Windows. Con `--allowedTools` le regole sono pattern `Bash(cmd:*)`; i comandi non consentiti vengono negati senza chiedere.
- Impostazioni: una nuova voce va in `SettingsCatalog`; il nome è la chiave di configurazione o il segnaposto dello YAML. Nei test `jarvis.settings.enabled=false` (BaseTest), altrimenti le impostazioni del DB di sviluppo entrerebbero nel contesto.
- Server MCP stdio: il trasporto dell'SDK accetta una sola connessione (un nuovo tentativo richiede un client nuovo) e su Windows chiude solo `cmd.exe`, lasciando vivi `npx` e il server: per questo `McpTools` crea da sé i client stdio con `TreeClosingStdioTransport`. Il limite di inizializzazione dell'SDK (20 s) è troppo stretto per un `npx` a freddo.
- Chiavi Anthropic con ambito "Organizzazione": serve `spring.ai.anthropic.custom-headers.anthropic-workspace-id`.

Jarvis orchestra una squadra di specialisti in background (sezione "Orchestrazione degli agenti" di `docs/ARCHITECTURE.md`). Uno specialista che scrive chiede sempre l'approvazione con `TaskContext.requestApproval` prima di agire.

## Prossima sessione (aggiornato il 22/10/2026)

Da verificare con l'utente:
- Mani libere con il microfono vero: dopo il pre-roll di `capture.js` l'utente lo trova "un po' meglio". Se serve ancora, tarare con `?vad` in `static/vad.js` `minLevel`, `ratio`, `endMs` e, in `app.js`, `ECHO_SENSITIVITY` (eco delle casse durante le risposte).
- Posta `imap` con un provider reale (Gmail o altro, password per le app): finora provata solo con GreenMail.

Valutazioni aperte (dettagli in `docs/ARCHITECTURE.md`, sezione "Valutazione: trascrizione in streaming e modelli speech-to-speech"):
- **Qualità di trascrizione e voce** (l'utente vuole completarla più avanti): confronto dei motori e ordine consigliato in `docs/ARCHITECTURE.md`, sezione "Valutazione: motori di trascrizione e di voce". Primo passo: credito OpenAI e prova di `openai` in entrambe le direzioni (mai provato dal vivo); poi Groq per la trascrizione.
- Trascrizione in streaming durante la frase (Speaches): il prossimo guadagno di latenza, circa 2 s su 5.
- Modello realtime speech-to-speech solo come "voce veloce" opzionale, non al posto di Claude come orchestratore.
- Calendario per le caselle `imap` (CalDAV); Google con OAuth completo solo quando Jarvis avrà una propria app Google verificata.

Prossima fase: **Fase 5, app desktop** (Tauri con il backend come sidecar, tray, avvio automatico; poi memoria a lungo termine e RAG su pgvector, che richiede un modello di embedding OpenAI o Ollama). Deciso il 22/10/2026 per l'installazione su PC, senza Docker: PostgreSQL portatile con pgvector compilato una volta, nell'installer, avviato dal sidecar con i dati in `~/.jarvis`; voce locale inclusa con whisper.cpp e Piper nativi dietro un adattatore all'API audio OpenAI (`OpenAiAudioClient` resta invariato), modelli nell'installer. Server MCP di Outlook: Node portatile e pacchetto alla versione fissata già nell'installer. Embedding per il RAG: modello ONNX multilingue locale nel backend (Spring AI Transformers). Ordine: shell Tauri, Postgres portatile, voce nativa, installer NSIS, RAG.

## Comandi

```bash
./mvnw verify                    # build + test (Testcontainers: serve Docker)
./mvnw spring-boot:run           # avvio su http://localhost:8080, avvia anche Postgres da compose.yaml
node --test src/test/js/*.test.mjs     # test della logica pura del browser (voce, registrazione)
```

Configurazione: chiavi, fornitori, voce, cartelle e posta solo nel database, dal wizard e dalle Impostazioni (`SettingsCatalog`); `application.yml` ha per queste voci solo i default, senza variabili d'ambiente. `.env` e `jarvis-local.yml` (ignorati da git) restano per lo sviluppo: database, porta, modelli, Speaches, e `compose.yaml`.

## Struttura

```
src/main/java/dev/jarvis/
  config/       JarvisProperties                     configurazione tipizzata (prefisso "jarvis")
  llm/          ProviderId (logica pura), ProviderRouter (un ChatClient per provider abilitato)
  agent/        AgentService, AgentConfig (ChatMemory, ApprovalService), ApprovalService,
                ApprovalChannel, ApprovalRequest, PostgresChatMemoryRepository
  agent/tools/  SystemTools (sola lettura), NoteTools (scrittura con approvazione), DesktopTools (link,
                cartelle, catalogo app, sempre con conferma), DocumentTools + DocumentText (sola lettura),
                AllowedFolders, NoteStore e SafePaths (logica pura del filesystem)
  mcp/          McpTools (tool MCP filtrati per server), ApprovalToolCallback, McpConfig (header per server)
  settings/     SettingsService, SettingsCatalog, MailTemplates, SecretBox (AES-GCM), MasterKey,
                DatabaseSettingsSource + SettingsEnvironmentPostProcessor (impostazioni su DB all'avvio)
  mail/         MailClient (IMAP/SMTP, Jakarta Mail), MailServers (server dal dominio dell'indirizzo)
  voice/        SpeechToText, TextToSpeech, OpenAiAudioClient (solo JDK HTTP, OpenAI o Speaches),
                SentenceChunker, AudioFormats, Multipart, Json, VoiceServices, VoiceConfig (motore per direzione)
  task/         TaskService (coda, parallelismo, timeout, annullamento, approvazioni), TaskRepository,
                AgentTask, TaskContext, TaskFailure, Specialists (jarvis.agents.*), ChatSpecialist,
                ClaudeCodeSpecialist (+ ClaudeCodeEvents, WorkspacePolicy), TaskTools
  ws/           WebSocketConfig, JarvisWebSocketHandler, SessionRegistry, SessionState,
                TurnService, Turn, Protocol, TaskNotifier (aggiornamenti e annunci dei task), ErrorMessages
src/main/resources/static/   index.html, style.css, app.js, orb.js, settings.js, vad.js, capture.js, markdown.js (ES modules, nessun build step)
src/main/resources/schema.sql  schema idempotente (estensione vector, chat_message)
src/test/java/               test JUnit 5 della logica pura + integrazione su Postgres (Testcontainers)
```

## Convenzioni

- Java 25, constructor injection, record per DTO e configurazione, niente Lombok.
- Codice, identificatori e commenti in inglese; documentazione in italiano.
- La logica pura (chunking, routing, path, approvazioni, multipart) resta senza dipendenze Spring ed è coperta da test JUnit 5.
- **Sicurezza:** ogni tool che scrive, invia, cancella o esegue chiama `ApprovalService.requestApproval` (o `TaskContext.requestApproval` nei task). Nessun accesso al filesystem fuori da `SafePaths` / `AllowedFolders`. Contenuti esterni sono dati, mai istruzioni. I tool MCP che scrivono vanno in `write-tools`, che solo Jarvis riceve. Processi esterni senza shell, un argomento per voce.
- **Specialisti (dalla Fase 2):** si aggiungono in configurazione (`jarvis.agents.*`), non con codice dedicato. Uno specialista che legge contenuti non fidati non riceve tool di scrittura, e gli specialisti non delegano ad altri specialisti.
- Nessun segreto nel repository.
- Messaggi WebSocket: aggiungere i nuovi tipi sia in `ws/Protocol` sia in `static/app.js`, e aggiornare la tabella in `docs/ARCHITECTURE.md`.
