# Jarvis — Architettura

Assistente AI personale con backend Java (Spring Boot 4.1 + Spring AI 2.0), più provider cloud e locali, interazione vocale e un'interfaccia animata che reagisce alla voce.

## Vista d'insieme

```
┌──────────────────────────── Frontend (browser, poi Tauri) ─────────────────────────────┐
│  orb.js      animazione canvas: idle · listening · thinking · speaking                 │
│  app.js      WebSocket client · microfono (MediaRecorder) · coda di riproduzione audio │
│              AnalyserNode sul microfono e sull'audio in uscita → livello per l'orb     │
└───────────────────────────────┬────────────────────────────────────────────────────────┘
                                │  WebSocket /ws
                                │  testo: messaggi JSON   ·   binario: audio registrato
┌───────────────────────────────▼───────────────── Backend (Spring Boot) ────────────────┐
│  ws/      JarvisWebSocketHandler · SessionRegistry · TurnService (un turno)            │
│  agent/   AgentService (ChatClient + memoria + tool) · ApprovalService · tools/        │
│  task/    TaskService · Specialists · TaskTools (squadra di agenti in background)      │
│  llm/     ProviderRouter → un ChatClient per provider (Anthropic · OpenAI · Ollama)    │
│  voice/   SpeechToText · TextToSpeech (API audio OpenAI o Speaches locale) · Chunker   │
│  config/  JarvisProperties (prefisso "jarvis")                                         │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

Il frontend gestisce tutto ciò che è audio e grafica; il backend coordina tutto il resto. Java non tocca mai il microfono o le casse: è più semplice, funziona uguale nel browser e in Tauri, e lo stesso segnale audio pilota l'animazione.

## Componenti

| Componente | Responsabilità |
|---|---|
| `ProviderRouter` | Trova i `ChatModel` configurati da Spring AI, tiene solo i provider abilitati e crea un `ChatClient` per ciascuno con system prompt e memoria condivisa. Cambiare provider a metà conversazione mantiene la cronologia. L'advisor della memoria sta dentro il ciclo dei tool, così chiamate e risultati dei tool restano in memoria. |
| `PostgresChatMemoryRepository` | Salva la conversazione su PostgreSQL (tabella `chat_message`), compresi chiamate e risultati dei tool, che il repository JDBC di Spring AI scarta. `MessageWindowChatMemory` ci applica sopra la finestra degli ultimi messaggi. |
| `AgentService` | Un turno di conversazione: prompt utente → streaming della risposta, con memoria (`conversationId`) e tool. Il ciclo tool → risultato → passo successivo lo esegue Spring AI. |
| `ApprovalService` | I tool che modificano qualcosa chiedono conferma all'utente e attendono la risposta (con timeout: nessuna risposta = negato). |
| `tools/` | `SystemTools` (sola lettura) e `NoteTools` (scrittura in una cartella sandbox, previa approvazione). Esempi del pattern da seguire per tutti i tool futuri. |
| `SpeechToText` / `TextToSpeech` | Interfacce, implementate da `OpenAiAudioClient` (solo `java.net.http`) verso l'API audio OpenAI o verso Speaches in locale, che espone la stessa API. `VoiceConfig` sceglie il motore per direzione da `jarvis.voice.stt` / `jarvis.voice.tts` (`local` · `openai` · `browser` · `off`); con `browser` il server non interviene e il client usa la Web Speech API. |
| `SentenceChunker` | Spezza lo stream di token in frasi complete, così il TTS parte alla prima frase invece di aspettare la risposta intera. |
| `TurnService` | Collega tutto per un turno: stato → token → frasi → audio ordinato → fine. Gestisce l'annullamento (barge-in). Gli errori diventano frasi brevi in italiano (`ErrorMessages`), pronunciate se il turno è vocale; il dettaglio resta nei log. |
| `InterruptedTurns` | Chiude la coda di una conversazione interrotta: risultato fittizio per le chiamate ai tool rimaste aperte e risposta parziale marcata come interrotta. Gira all'annullamento e all'inizio di ogni turno, così copre anche i crash. |

## Flusso vocale

1. L'utente tiene premuto il tasto microfono (o la barra spaziatrice): il browser registra con `MediaRecorder` e l'orb va in `listening` seguendo il livello del microfono.
2. Al rilascio, l'audio (webm/opus) parte come frame **binario** sul WebSocket.
3. Il backend lo trascrive (`SpeechToText`) e rimanda `transcript`.
4. `AgentService` avvia lo streaming verso il provider selezionato; l'orb va in `thinking`.
5. Ogni token va al client (`token`) e al `SentenceChunker`.
6. Ogni frase completa va al TTS, su un esecutore a thread singolo, così l'ordine è garantito; l'audio torna come `audio` con un numero di sequenza.
7. Il client accoda e riproduce l'audio in ordine; l'orb va in `speaking` seguendo il livello dell'audio in uscita.
8. Se l'utente riprende a parlare mentre Jarvis risponde, il client ferma l'audio e invia `cancel`: il backend annulla lo stream e il TTS in corso, e chiude il turno in memoria (`InterruptedTurns`). Lo stesso succede se la connessione cade: alla riconnessione il client riceve `history` se la pagina è stata ricaricata.

Latenza percepita ≈ trascrizione + primo token + TTS della prima frase. Le leve principali sono un modello più veloce per la voce, frasi iniziali brevi e, più avanti, STT in streaming.

## Protocollo WebSocket

Client → server

| Tipo | Campi | Note |
|---|---|---|
| `chat` | `text`, `provider?`, `speak` | Messaggio scritto |
| *(binario)* | — | Audio registrato; risposta sempre parlata |
| `set-provider` | `provider` | Provider per i turni successivi |
| `cancel` | — | Interrompe il turno in corso |
| `approval` | `id`, `approved` | Risposta a una richiesta di approvazione |
| `settings-get` | — | Chiede le impostazioni |
| `settings-save` | `values`, `cleared` | Salva (i segreti vuoti restano come sono) e riavvia |
| `settings-test` | `name`, `value?` | Verifica una chiave senza salvarla |
| `settings-reveal` | `name` | Mostra in chiaro una chiave |
| `account-login` · `account-verify` | `server` | Login dell'account di posta |

Server → client

| Tipo | Campi |
|---|---|
| `hello` | `providers`, `defaultProvider`, `stt`, `tts` (`server` · `browser` · `off`), `language`, `setupRequired` |
| `history` | `messages` (`role`: `user` · `assistant`, `text`): ultimi scambi della conversazione, inviati dopo `hello`. Il client li mostra solo se il log è vuoto (pagina ricaricata) |
| `state` | `state` (`idle` · `thinking` · `speaking`) |
| `transcript` | `text` |
| `token` | `text` |
| `audio` | `seq`, `mime`, `data` (base64) |
| `approval-request` | `id`, `description`, `agent`, `taskTitle` (null se chiede Jarvis) |
| `approval-closed` | `id`: la richiesta è stata risolta, è scaduta o è stata ritirata |
| `settings` | `settings` (nome, gruppo, etichetta, aiuto, tipo, scelte, valore, `set`, `origin`; mai i segreti), `setupRequired` |
| `settings-saved` | `restarting` |
| `settings-error` | `message` |
| `settings-test-result` | `name`, `ok`, `message` |
| `settings-secret` | `name`, `value`: solo in risposta a `settings-reveal` |
| `account-result` | `server`, `message` (login con codice, verifica) |
| `done` | — |
| `error` | `message` |

## Agente e sicurezza

- Il ciclo agente è quello di Spring AI: il modello chiede un tool, Spring AI lo esegue e rimanda il risultato finché il modello non risponde in testo.
- **Regola:** ogni tool che scrive, invia, cancella o esegue passa da `ApprovalService`. I tool di sola lettura no.
- I tool che toccano il filesystem lavorano solo dentro una cartella sandbox (`jarvis.agent.notes-dir`), con controllo anti path-traversal.
- Contenuti esterni (email, pagine web, file) sono **dati, non istruzioni**: quando arriveranno i tool che li leggono, il system prompt e l'approvazione obbligatoria restano la difesa contro la prompt injection.
- Shell e comandi di sistema arriveranno solo con allowlist esplicita, ed eventualmente in container.

## Orchestrazione degli agenti (Fase 2)

Jarvis è il capo di una squadra di specialisti. Per ogni richiesta decide se rispondere da solo, usare un tool o affidare il lavoro a uno o più specialisti in background, in parallelo. La conversazione resta libera: quando un lavoro finisce, Jarvis lo riferisce, anche a voce.

### Componenti

| Componente | Responsabilità |
|---|---|
| `Specialists` | Costruisce uno specialista per ogni voce di `jarvis.agents.*`, di due tipi: `chat` (`ChatSpecialist`: un `ChatClient` con provider, modello, system prompt, tool di sola lettura e limiti) e `claude-code` (`ClaudeCodeSpecialist`, il Programmatore). Aggiungere uno specialista è solo configurazione. Valida la configurazione all'avvio. |
| `TaskService` | Coda e esecuzione su thread virtuali, al massimo `jarvis.tasks.max-parallel` task insieme; timeout (`jarvis.tasks.timeout`), annullamento, consumo di token. Ogni cambio di stato è salvato su `agent_task` e pubblicato come evento `TaskUpdated`. All'avvio i task rimasti a metà diventano `interrupted`. |
| `TaskTools` | Gli strumenti del solo orchestratore: `startTask(agent, title, instructions, expectedOutput, workspace)`, `listTasks`, `listRepositories`, `cancelTask(id \| all)`. Gli specialisti non li ricevono, quindi non possono delegare. |
| `TaskContext` | Quello che uno specialista può fare oltre a rispondere: riportare l'avanzamento (mostrato in diretta) e chiedere un'approvazione. Durante l'attesa il task è `waiting_approval` e il suo timeout è in pausa. |
| `TaskNotifier` | Inoltra ogni aggiornamento alle schede aperte della conversazione (`task-update`) e, a task finito, consegna l'esito a Jarvis come messaggio automatico. Se Jarvis sta parlando, il messaggio aspetta la fine del turno; più esiti pronti insieme partono in un solo turno. |

### Delegare significa chiamare un tool

Un unico tool `startTask` con l'id dello specialista: il modello sceglie leggendo le descrizioni che `ProviderRouter` aggiunge al system prompt. Per lavori indipendenti lo chiama più volte nella stessa risposta, e i task girano in parallelo. Risponde subito con una frase ("ci lavoro") senza aspettare.

Lo specialista parte con un **contesto pulito**: riceve solo istruzioni e risultato atteso, non la conversazione. Costa meno token, non si confonde con discorsi precedenti e non vede dati che non gli servono. Il suo risultato torna a Jarvis in un messaggio automatico marcato (`[Aggiornamento automatico sui task…]`), che resta in memoria ma non compare come messaggio dell'utente.

Specialisti configurati:

| Specialista | Compito | Tool | Scrittura |
|---|---|---|---|
| `researcher` | Cerca sul web, confronta fonti, restituisce sintesi e link | `time`, `web-search` (ricerca lato Anthropic, `max-searches` come budget) | No |
| `writer` | Scrive testi completi da istruzioni dettagliate | `time` | No |
| `coder` | Lavora su un repository git locale con Claude Code | file, git locale, build e test consentiti | Solo dopo l'approvazione del piano, su un branch nuovo, mai push |

| `documents` | Legge, cerca e riassume documenti (testo, PDF, Word) nelle cartelle di `jarvis.documents.folders` | `time`, `documents` | No |
| `github` | Legge repository, issue, PR, codice e commit tramite il server MCP di GitHub | `time`, `mcp:github` (tool di lettura) | No |
| `organizer` | Legge posta e calendario (esempio in configurazione, da collegare al server MCP del proprio fornitore) | `time`, `mcp:mail`, `mcp:calendar` | No |

Le azioni che cambiano qualcosa all'esterno (creare una issue o una PR, inviare un'email, creare un evento) non sono mai degli specialisti: sono i `write-tools` dei server MCP, dati solo a Jarvis e ciascuno eseguito dopo la tua approvazione dei parametri esatti. Previsti più avanti: agenti esterni via A2A.

### Il Programmatore (`coder`)

Claude Code lanciato come processo esterno (`claude -p`, eventi `stream-json`), sul repository indicato in `startTask`:

1. **Controlli:** il repository deve stare sotto `workspaces` (percorso risolto per intero: `..` e link non escono) ed essere un repo git senza modifiche in sospeso.
2. **Piano:** `--permission-mode plan`, quindi sola lettura. Ogni chiamata ai tool diventa avanzamento leggibile ("Legge greet.py", "Esegue ./mvnw verify").
3. **Approvazione:** il piano ti arriva come richiesta del task; se lo rifiuti il task è annullato e il repository non viene toccato.
4. **Lavoro:** Jarvis crea `jarvis/<titolo>-<id>`, poi la stessa sessione riprende (`--resume`) con `acceptEdits` e i soli comandi di `allowed-commands`; `git push` e `git remote` sono sempre vietati.
5. **Push, se vuoi:** a lavoro finito, se ci sono commit e un remote `origin`, ti chiede se fare il push del branch. Lo esegue Jarvis stesso con le tue credenziali git (`GIT_TERMINAL_PROMPT=0`, timeout); a Claude Code il push resta vietato.
6. **Resoconto:** branch, commit, file modificati, esito del push, eventuali modifiche rimaste senza commit, costo.

Isolamento: `--bare` salta hook e plugin dell'utente e si autentica **solo** con la chiave Anthropic di Jarvis (stesso workspace, mai il login del PC); `--restricted` ignora ogni file di impostazioni e confina i tool sui file alla cartella del repository. Il `CLAUDE.md` del progetto, che `--bare` non legge da solo, viene passato esplicitamente. Budget: `max-budget-usd` per task, piano compreso; il tempo massimo (`timeout`) esclude l'attesa della tua approvazione.

### Sicurezza e controllo

- **Permessi per specialista:** ognuno vede solo i propri tool, scelti da un insieme che oggi non contiene nulla che scrive, invia o cancella.
- **Lettura separata dall'azione:** chi legge contenuti non fidati (il web) non ha tool di scrittura. Le azioni le decide l'orchestratore, sulla base del riassunto, e passano comunque dall'approvazione: un riassunto può ancora contenere istruzioni iniettate.
- **Una sola profondità di delega:** gli specialisti non delegano a loro volta.
- **Budget:** ricerche web per task (`max-searches`), token di uscita (`max-tokens`), durata (`jarvis.tasks.timeout`), parallelismo (`max-parallel`). Il modello può lanciare le ricerche da codice in un ciclo, e ogni query conta.
- **Annullamento:** pulsante "Ferma" nel pannello Squadra, `cancel-task` dal client, oppure "Jarvis, ferma tutto" (`cancelTask("all")`).
- **Tracciabilità:** ogni task registra stato, tempi e token; li vedi nel pannello Squadra e nel log JSON (`Task ended`).
- **Approvazioni legate alla conversazione:** qualsiasi scheda della conversazione può rispondere; quelle di Jarvis valgono finché dura il suo turno, quelle dei task sopravvivono alle disconnessioni (`jarvis.tasks.approval-timeout`, 30 minuti) e vengono ripresentate quando ti riconnetti. Quando una richiesta si chiude (risposta, scadenza, task fermato) le altre schede la tolgono (`approval-closed`).

### Strumenti esterni (MCP) e azioni sul desktop

- **MCP:** i server sono connessioni di `spring.ai.mcp.client.*` (HTTP streamable o stdio); `jarvis.mcp.servers.<nome>` dice quali tool esporre. `McpTools` li inizializza all'avvio in modo tollerante (un server irraggiungibile o senza token viene saltato con un warning) e li espone come `<server>_<tool>`: i `read-tools` agli specialisti che dichiarano `mcp:<server>`, i `write-tools` solo a Jarvis, dietro approvazione (`ApprovalToolCallback`). Gli header (per esempio il token) li aggiunge un customizer delle richieste, solo verso l'URL di quel server. Così ognuno collega i propri account (GitHub, posta, calendario) con la sola configurazione.
- **Desktop (`DesktopTools`, solo Jarvis, sempre con conferma):** `openUrl` (solo http/https), `openFolder` (cartelle esistenti), `launchApp` sul catalogo `jarvis.desktop.apps` (comando ed eventuale `{target}`, che deve esistere), `listApps`. I processi partono senza shell, un argomento per voce: nulla in un link, un percorso o un nome può iniettare un comando. Il catalogo, non il modello, decide quali programmi esistono: un elenco libero di comandi trasformerebbe una prompt injection in esecuzione di codice, protetta solo dalla tua attenzione nel momento della conferma.

### Interfaccia

- **Orb:** ogni task attivo è un satellite nel colore del suo agente, su un'orbita inclinata intorno alla lampada. In coda resta fermo e tenue; al lavoro orbita con una scia; quando finisce dà un lampo e si dissolve.
- **Header:** lo stato della squadra in parole ("2 agenti al lavoro, 1 in coda") con un punto per agente.
- **Pannello Squadra:** colonna fissa sopra i 1200 px, pannello a scomparsa sotto. Per ogni lavoro: agente, titolo, stato e tempo in diretta, "Ferma", e nel dettaglio risultato, motivo, istruzioni e token. Gli annunci per gli screen reader passano da una regione `aria-live` separata.
- Grassetto e link nei testi di Jarvis e degli agenti sono resi costruendo il DOM, mai con `innerHTML`, e i link accettano solo `http(s)`.

### Protocollo

| Direzione | Tipo | Campi |
|---|---|---|
| server → client | `tasks` | `tasks`: elenco recente della conversazione, inviato dopo `history` |
| server → client | `task-update` | `task`: `id`, `agent`, `title`, `instructions`, `workspace`, `status` (`queued` · `running` · `waiting_approval` · `completed` · `failed` · `cancelled` · `interrupted`), `progress`, `result`, `error`, `inputTokens`, `outputTokens`, `costUsd`, `createdAt`, `startedAt`, `finishedAt` (epoch ms) |
| client → server | `cancel-task` | `id` |

Un messaggio di stato completo, invece di eventi separati per inizio, avanzamento e fine, rende banale il client (sostituisce per `id`) e la riconnessione (basta `tasks`).

## Persistenza

- PostgreSQL 18 con pgvector (`compose.yaml`, porta 5433 su loopback). `./mvnw spring-boot:run` avvia il container grazie al supporto Docker Compose di Spring Boot; con `java -jar` va avviato prima con `docker compose up -d`.
- Lo schema è in `schema.sql`, idempotente ed eseguito a ogni avvio. Alla prima modifica incompatibile si passa a Flyway.
- L'estensione `vector` è già attiva: servirà alla memoria a lungo termine e al RAG (Fase 5), con un modello di embedding OpenAI o Ollama, perché Anthropic non ne offre.
- I test d'integrazione usano Testcontainers con la stessa immagine.

## Impostazioni (Fase 3b)

Jarvis va installato su un PC, dove un file `.env` non è una soluzione: le impostazioni stanno nel database, scelte dal wizard del primo avvio e modificabili dalla sezione Impostazioni (icona nell'header).

- **Tabella `setting`** (`name`, `kind` text · secret · list, `value`). I nomi sono le stesse chiavi della configurazione (`jarvis.voice.stt`) o i segnaposto di `application.yml` (`ANTHROPIC_API_KEY`, `GITHUB_TOKEN`).
- **Segreti cifrati** con AES-256-GCM (`SecretBox`); il nome dell'impostazione è dato autenticato, quindi un valore copiato sotto un altro nome non si decifra. La chiave master non sta nel database: `~/.jarvis/master.key`, generata al primo avvio (o `JARVIS_MASTER_KEY`). Protegge un dump o un backup del DB, non da chi può accedere con l'utente del PC.
- **Lettura all'avvio:** `SettingsEnvironmentPostProcessor` aggiunge `DatabaseSettingsSource` subito dopo le variabili d'ambiente: le impostazioni vincono su `application.yml` e `.env`, una variabile d'ambiente o un argomento da riga di comando vincono ancora. La sorgente si carica al primo uso con JDBC semplice (il DataSource non esiste ancora) e, se il database sta ancora partendo, riprova al massimo una volta al secondo; una tabella che non esiste ancora vale come "nessuna impostazione". Le liste salvate sostituiscono per intero quelle dello YAML.
- **Applicazione:** dopo il salvataggio Jarvis si riavvia nello stesso processo (`JarvisApplication.restart`), le pagine si riconnettono da sole; i container di sviluppo restano accesi (`lifecycle-management: start-only`).
- **Sicurezza:** tutto passa dal WebSocket, che accetta solo pagine della stessa origine. L'elenco delle impostazioni non contiene mai i segreti: la pagina sa solo se sono impostati e da dove (impostazioni, variabile d'ambiente, file). Il valore in chiaro arriva solo premendo l'occhio accanto al campo, una chiave alla volta (`settings-reveal`). "Verifica" prova la chiave contro il servizio senza salvarla.
- **Posta e calendario:** scegliere `outlook` scrive un modello (`MailTemplates`): la connessione MCP a `@softeria/ms-365-mcp-server` (versione fissata, tenant `consumers` per gli account personali), i tool esposti (lettura all'Organizer, invii e modifiche a Jarvis con approvazione, nessuna cancellazione) e lo specialista `organizer`. L'accesso è un login Microsoft con codice (pulsanti "Collega l'account" e "Ho fatto l'accesso"); i token li conserva il server MCP, cifrati, con la chiave nel Credential Manager di Windows. Un server che all'avvio non risponde in tempo (20 s, per esempio `npx` che scarica il pacchetto) viene riprovato in background ogni 15 secondi per un paio di minuti: i suoi tool compaiono appena è pronto, perché specialisti e Jarvis li leggono al momento d'uso.
- **Altri provider di posta:** `microsoft365` usa lo stesso server con l'autorità `organizations` (account di lavoro o scuola; il tenant può richiedere il consenso dell'amministratore). `imap` usa i tool di posta integrati in Jarvis (`MailClient`, Jakarta Mail): indirizzo e *password per le app*, cifrata come gli altri segreti; i server IMAP e SMTP si ricavano dall'indirizzo per Gmail, iCloud, Yahoo, Libero, Fastmail e GMX (`MailServers`), altrimenti si indicano come host o host:porta. Connessioni sempre cifrate (TLS o STARTTLS obbligatorio); la lettura usa IMAP PEEK, quindi non segna i messaggi come letti. Lettura (`listRecentEmails`, `searchEmails`, `readEmail`) all'Organizer, invio e risposta (`sendEmail`, `replyToEmail`) solo a Jarvis, dopo l'approvazione del messaggio esatto. Solo posta: il calendario per questi provider (CalDAV) è un passo successivo.

## Configurazione e segreti

- `application.yml` contiene solo valori di default e riferimenti a variabili d'ambiente.
- Le chiavi vanno in variabili d'ambiente oppure in `jarvis-local.yml` o `.env` nella cartella da cui si avvia l'app (in `.gitignore`, importati con `spring.config.import`).
- Un provider cloud senza chiave resta configurato con un segnaposto, così l'app parte comunque; basta non includerlo in `jarvis.providers.enabled`.

## Decisioni

1. **Spring Boot 4.1 + Spring AI 2.0.** Astrazione multi-provider, tool calling, memoria come advisor e supporto MCP maturo.
2. **Audio nel browser, non in Java.** Web Audio API per cattura, riproduzione e analisi del livello; il backend riceve e invia solo byte.
3. **STT/TTS con HTTP diretto** invece delle API audio di Spring AI. Il modulo OpenAI è stato riscritto nella 2.0; tenere l'audio dietro interfacce nostre con il solo JDK riduce le dipendenze. I motori locali passano da Speaches, che espone l'API audio OpenAI: stesso client, cambia solo l'indirizzo.
4. **Approvazione dentro i tool.** Funziona con il ciclo automatico di Spring AI e mette la regola di sicurezza accanto al codice che la richiede.
5. **TTS sequenziale per turno.** Ordine garantito senza complessità; la parallelizzazione con riordino lato client è un'ottimizzazione successiva (il campo `seq` è già previsto).
6. **Orchestrazione: delega come tool.** Un solo tool `startTask` con l'id dello specialista, invece di un tool per specialista: stesso effetto, nessun `ToolCallback` generato a runtime. Gli specialisti partono con un contesto pulito, delegano su un solo livello, e chi legge contenuti non fidati non può scrivere. Tutti i lavori delegati girano in background, così la conversazione vocale non si blocca mai.
7. **PostgreSQL + pgvector da subito.** Un solo database per conversazioni, task (Fase 2) e vettori (Fase 5), invece di un database su file da migrare più avanti.
8. **Voce locale di default: Whisper `base` + Piper Paola**, misurati su CPU da portatile (Core Ultra 7 165U, senza GPU dedicata). Su una frase di 2,5 s: Whisper `base` circa 2 s, `small` circa 5,5 s; Piper circa 0,2 s per frase, Kokoro circa 5 volte più lento. Con una GPU conviene `small` o `large-v3-turbo` e l'immagine `latest-cuda`.
9. **Haiku per i turni parlati** (`jarvis.providers.voice-models`). Misurato: circa 2 s al primo audio contro 4–20 s di Sonnet, che resta il modello dei turni scritti. Vale anche quando la voce la produce il browser.

## Roadmap

- **Fase 0 — questo scheletro.** Chat multi-provider in streaming, push-to-talk, TTS per frase, orb animato, tool d'esempio con approvazione.
- **Fase 1 — base solida (fatta).** Build verde, Maven wrapper, test, gestione errori e riconnessione, persistenza su PostgreSQL + pgvector (i task arriveranno con la Fase 2), log strutturati (file JSON ECS in `~/.jarvis/logs/`).
- **Fase 2 — task e orchestrazione (fatta).** Task in background in parallelo, eventi, pannello Squadra e satelliti nell'orb; delega come tool; specialisti `researcher` e `writer` in sola lettura; budget, timeout e annullamento. Le approvazioni per conversazione passano alla Fase 3, con i primi specialisti che scrivono.
- **Fase 3 — tool reali e nuovi specialisti (fatta).** Approvazioni per conversazione; Programmatore (`coder`) con Claude Code e push su autorizzazione; client MCP con tool filtrati per server (lettura agli specialisti, scrittura a Jarvis con approvazione), GitHub collegato; specialisti `documents`, `github` e `organizer` (quest'ultimo da collegare al server MCP del proprio fornitore di posta e calendario); azioni desktop su catalogo.
- **Fase 3b — installazione e impostazioni (fatta).** Wizard del primo avvio e sezione Impostazioni; valori su DB, segreti cifrati con AES-256-GCM e chiave master fuori dal DB; riavvio interno per applicarli; Organizer collegato a Outlook.com/Hotmail.
- **Fase 4 — voce naturale.** VAD nel browser e barge-in automatico, wake word, STT in streaming, valutazione di un'API realtime speech-to-speech. STT/TTS locali e Web Speech API sono già disponibili.
- **Fase 5 — app desktop.** Shell Tauri con il backend come sidecar, tray, avvio automatico; memoria a lungo termine e RAG sui documenti (pgvector).
- **Fase 6 — autonomia controllata.** Routine pianificate (briefing del mattino), agenti esterni via A2A, specialista valutatore che verifica i risultati, eventuale delega su più livelli.
