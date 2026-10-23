# Memoria di sviluppo (per riprendere su un altro PC)

Questo file raccoglie quello che Claude Code ricordava del progetto nella sua memoria locale (`~/.claude/projects/.../memory`), che non viaggia con il repository. Aggiornato il 23/10/2026.

Lo stato del lavoro, le cose verificate su Spring AI e il piano della prossima sessione sono in `CLAUDE.md`; l'architettura in `docs/ARCHITECTURE.md`; come rigenerare l'app desktop nel `README.md`. Qui c'è solo quello che non sta già lì.

## Da dove si riparte

Prima cosa della prossima sessione: **il multilingua, italiano e inglese**, con il piano in `CLAUDE.md` ("Prossima sessione"), da fare insieme all'utente. Ordine proposto: lingua unica nel Profilo → interfaccia (`i18n.js`) → testi del server → voce → date e numeri, poi i prompt con una prova di qualità.

Restano da fare, dopo: la prova dell'installer da parte dell'utente (rigenerarlo con il Node incluso), la firma del codice, gli script e i binari per macOS e Linux, poi la Fase 6 (autonomia controllata).

Da verificare con l'utente: mani libere con il microfono vero (taratura VAD), posta `imap` con un provider reale.

## Preferenze dell'utente (come lavorare)

- **Mai cancellare la storia.** "Cancellare" chat o task dall'interfaccia li nasconde soltanto (e svuota la finestra breve del modello). I messaggi restano in `chat_archive` (solo in aggiunta), i task con `agent_task.hidden`: sono la fonte della memoria a lungo termine. Nessuna funzione nuova deve fare un vero `DELETE` dei dati di conversazione; la memoria legge anche i task nascosti.
- **Multipiattaforma.** Ogni funzione e ogni passo di build deve funzionare su Windows, macOS e Linux: si parte da Windows, ma il codice specifico del sistema resta isolato (`cfg`, controlli sul sistema operativo) e la controparte macOS/Linux va annotata nella tabella "Multipiattaforma" di `docs/ARCHITECTURE.md`. iOS solo come client di un Jarvis che gira altrove (progetto separato).
- **Lingue.** Oggi solo italiano; serve almeno l'inglese, le altre lingue da valutare. Non scrivere testi per l'utente in modo che sia difficile tradurli.
- **File temporanei.** Script di prova, log e screenshot in una sola cartella sotto `%TEMP%` (per esempio `%TEMP%/jarvis-work`), da cancellare alla fine del lavoro; mai in `target/`, che tiene solo l'output di Maven. Le cache vere (`desktop/.cache`, build di Rust e Maven, `~/.jarvis`) restano.
- Le decisioni già prese (sotto) non vanno richieste di nuovo.

## Decisioni della Fase 5 (distribuzione senza Docker)

- **PostgreSQL:** binari portabili più pgvector compilato una volta, inclusi nell'installer; la shell lo avvia su una porta locale con i dati in `~/.jarvis`.
- **Voce:** locale e inclusa, whisper.cpp e Piper nativi avviati da Jarvis, compatibili con l'API audio di OpenAI (così `OpenAiAudioClient` resta lo stesso); installer più pesante, accettato.
- **Server MCP di Outlook:** Node portabile più il pacchetto `@softeria/ms-365-mcp-server` a versione fissa, già scaricati nell'installer (niente download nel wizard).
- **Embedding della memoria:** `multilingual-e5-small` (ONNX) dentro il backend con ONNX Runtime (`OnnxEmbeddings`), niente OpenAI né Ollama. Scartato Spring AI Transformers: si portava dietro il motore PyTorch di DJL, che scarica circa 260 MB al primo uso.
- Fatti tutti i passi: shell Tauri, Postgres, voce nativa, installer, memoria (documenti inclusi), Node portabile.

## Il PC e la rete (cosa replicare sul PC nuovo)

Sul vecchio PC (portatile aziendale, Windows 11):

- **JDK 25** in `C:/Users/<utente>/.jdks/openjdk-25.0.2`; il `java` di sistema era il 17, quindi `./mvnw` va lanciato con `JAVA_HOME` sul 25. Per `npm run backend` serve `-JavaHome <JDK 25>`.
- **Rete aziendale con Netskope**, che ispeziona il TLS di alcuni host (CDN di Hugging Face, probabilmente Microsoft Graph per Node). La CA radice è esportata in `.certs/` (ignorata da git); `SPEACHES_CA_BUNDLE` e `NODE_EXTRA_CA_CERTS` in `.env` puntano lì. Java raggiunge `api.anthropic.com` e GitHub anche senza. Su un PC senza Netskope queste due variabili non servono.
- **Porta 5432** occupata dal Postgres di un altro progetto: Jarvis in sviluppo usa la **5433** (`compose.yaml`).
- **Solo CPU** (Core Ultra 7 165U, niente NVIDIA): Whisper `base` (o `small`, più lento) e Piper sono i modelli locali realistici. Su un PC più potente vale la pena rimisurare `small`.
- In Git Bash i path per Docker richiedono `MSYS_NO_PATHCONV=1` (e con quella variabile usare `C:/...`, non `/c/...`).
- **Git:** il repository (`github.com/JuMp3/jarvis`) usa l'email personale `jmusic@hotmail.it` nella configurazione locale, mai quella di lavoro:
  `git config user.email jmusic@hotmail.it` dopo il clone.

## File che git non porta (da copiare o rifare)

| Cosa | Dove | Come |
|---|---|---|
| `.env` | radice del repo | copiare; contiene `ANTHROPIC_API_KEY`, `spring.ai.anthropic.custom-headers.anthropic-workspace-id` (chiave con ambito Organizzazione), `OPENAI_API_KEY`, `GITHUB_TOKEN`, `JARVIS_VOICE_HOME`, `JARVIS_MEMORY_HOME`, le CA di Netskope. Aggiornare i percorsi |
| `.certs/` | radice del repo | solo se anche il PC nuovo è dietro Netskope |
| Chiave principale | `~/.jarvis/master.key` | cifra i segreti delle Impostazioni nel DB: senza questa i segreti salvati non si leggono più. Copiarla insieme al DB, oppure rifare il wizard |
| DB di sviluppo | volume Docker `jarvis-pgdata` | `pg_dump` sul vecchio PC e ripristino sul nuovo, se si vogliono tenere conversazioni, task e impostazioni; altrimenti parte vuoto e si rifà il wizard |
| Dati dell'app desktop | `~/.jarvis` (`pgdata/`, `documents/`, `logs/`, `db.password`, `master.key`) | copiare la cartella intera se si vuole lo stesso stato dell'app installata |
| Voce, memoria, Postgres e Node per la shell | `desktop/src-tauri/resources/*`, `desktop/.cache` | rifare con gli script `desktop/prepare-*.ps1` (comandi in `CLAUDE.md`) |
| `jarvis-local.yml` | radice del repo | facoltativo, non c'era sul vecchio PC |

Strumenti da installare: JDK 25, Docker (per `./mvnw verify` con Testcontainers e per `spring-boot:run`), Node e npm, Rust (cargo) per la shell Tauri, Visual Studio 2022 solo se si deve ricompilare pgvector.
