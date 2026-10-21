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
│  task/    TaskService · AgentRegistry · specialisti            (previsto, Fase 2)      │
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

Server → client

| Tipo | Campi |
|---|---|
| `hello` | `providers`, `defaultProvider`, `stt`, `tts` (`server` · `browser` · `off`), `language` |
| `history` | `messages` (`role`: `user` · `assistant`, `text`): ultimi scambi della conversazione, inviati dopo `hello`. Il client li mostra solo se il log è vuoto (pagina ricaricata) |
| `state` | `state` (`idle` · `thinking` · `speaking`) |
| `transcript` | `text` |
| `token` | `text` |
| `audio` | `seq`, `mime`, `data` (base64) |
| `approval-request` | `id`, `description` |
| `done` | — |
| `error` | `message` |

## Agente e sicurezza

- Il ciclo agente è quello di Spring AI: il modello chiede un tool, Spring AI lo esegue e rimanda il risultato finché il modello non risponde in testo.
- **Regola:** ogni tool che scrive, invia, cancella o esegue passa da `ApprovalService`. I tool di sola lettura no.
- I tool che toccano il filesystem lavorano solo dentro una cartella sandbox (`jarvis.agent.notes-dir`), con controllo anti path-traversal.
- Contenuti esterni (email, pagine web, file) sono **dati, non istruzioni**: quando arriveranno i tool che li leggono, il system prompt e l'approvazione obbligatoria restano la difesa contro la prompt injection.
- Shell e comandi di sistema arriveranno solo con allowlist esplicita, ed eventualmente in container.

## Orchestrazione degli agenti (progetto, Fase 2)

Non ancora implementata: oggi c'è un solo agente (`AgentService`). Questa sezione fissa il disegno, così le fasi successive non lo contraddicono.

### Ruoli

- **Orchestratore.** È Jarvis, cioè l'agente della conversazione. Per ogni richiesta decide se rispondere da solo, usare un tool, delegare a uno specialista o avviare un task in background. Poi verifica i risultati e ti riferisce.
- **Specialisti.** Ognuno è definito in configurazione da: id, descrizione (è quella che legge l'orchestratore per scegliere), system prompt, provider e modello, tool consentiti, permessi e budget. Primi candidati:

  | Specialista | Compito | Tool | Scrittura |
  |---|---|---|---|
  | `researcher` | Cerca e confronta informazioni sul web | ricerca, lettura pagine | No |
  | `documents` | Legge e riassume documenti locali | file sandbox, RAG | No |
  | `organizer` | Calendario ed email | MCP calendario, IMAP/SMTP | Con approvazione |
  | `coder` | Lavora su un repository locale | git, file, build; in alternativa Claude Code come processo esterno | Con approvazione |

- **Agenti esterni.** Agenti remoti via protocollo A2A, o tool CLI come Claude Code, presentati con la stessa interfaccia degli specialisti interni.

### Delegare significa chiamare un tool

Per l'orchestratore ogni specialista è un tool (`delegate_researcher`, `delegate_documents`…) con due parametri: `task` (istruzioni complete e autosufficienti) ed `expectedOutput` (cosa restituire). In questo modo il ciclo agente di Spring AI resta lo stesso, e la scelta dello specialista la fa il modello leggendo le descrizioni.

Lo specialista parte con un **contesto pulito**: riceve solo il compito, non la conversazione. Costa meno token, non si confonde con discorsi precedenti e non vede dati che non gli servono. Restituisce un risultato sintetico (esito, risultato, fonti o file prodotti, consumo), non la trascrizione del suo lavoro.

```java
public interface Agent {
    AgentSpec spec();
    AgentResult run(AgentTask task, TaskContext context);   // bloccante, su thread virtuale
}

record AgentSpec(String id, String description, ProviderId provider, String model,
                 Set<String> tools, boolean canWrite, Budget budget) {}
record AgentTask(String taskId, String parentTaskId, String instructions, String expectedOutput) {}
record AgentResult(Outcome outcome, String summary, List<Artifact> artifacts, Usage usage) {}
// TaskContext: conversationId (per le approvazioni), annullamento, contatore del budget, canale eventi
```

### Sincrono o in background

- **Richieste brevi** (una domanda, un tool veloce): restano nel turno vocale come oggi.
- **Lavori lunghi:** l'orchestratore chiama `start_task(title, plan)`, che crea un task e restituisce subito un id. Jarvis risponde "ci lavoro" e la conversazione resta libera. `TaskService` esegue il task in background e pubblica eventi di avanzamento. Alla fine il risultato entra nella conversazione e Jarvis lo annuncia, anche a voce se attiva.
- **Parallelismo:** un task può avviare più sotto-task, per esempio tre ricerche su fonti diverse, eseguiti in parallelo con un limite di concorrenza.
- **Ciclo di vita:** `queued → running ⇄ waiting-approval → completed | failed | cancelled`.
- **Persistenza:** i task si salvano su database (dopo la Fase 1), così lo storico sopravvive ai riavvii. Un task in corso durante uno spegnimento viene segnato come interrotto.

### Sicurezza e controllo

- **Permessi per specialista:** ognuno vede solo i propri tool. Il `researcher` non può inviare email.
- **Lettura separata dall'azione:** chi legge contenuti non fidati (web, email in arrivo) non ha tool di scrittura. Le azioni le decide l'orchestratore, sulla base del riassunto, e passano comunque dall'approvazione: un riassunto può ancora contenere istruzioni iniettate.
- **Approvazioni legate alla conversazione:** un task può durare più di una connessione WebSocket, quindi le richieste di approvazione vanno instradate per `conversationId` e non per sessione. Vengono ripresentate quando ti riconnetti e hanno un timeout più lungo dei turni vocali. La richiesta dice quale specialista la fa e per quale task.
- **Una sola profondità di delega:** gli specialisti non delegano a loro volta. Evita cicli e costi fuori controllo; si potrà rivedere più avanti.
- **Budget:** limite di passi (chiamate ai tool), token e durata per task e per specialista. Superato il limite, lo specialista si ferma e restituisce un risultato parziale. Per far rispettare budget e annullamento a ogni passo, il ciclo dei tool degli specialisti va guidato a mano (Spring AI 2.0 permette di disattivare quello automatico).
- **Annullamento:** "Jarvis, ferma tutto" o il pulsante nel pannello attività cancellano il task e i suoi sotto-task.
- **Tracciabilità:** ogni task registra passi, tool chiamati, token e costo stimato. Li vedi nel pannello attività e nei log.

### Quando non delegare

Il system prompt dell'orchestratore dice di rispondere direttamente quando può. Delega solo se il lavoro è lungo, si può parallelizzare, richiede un modello o permessi diversi, oppure deve leggere molti contenuti esterni. Più agenti costano molti più token e sono più difficili da capire quando sbagliano.

### Protocollo previsto

| Direzione | Tipo | Campi |
|---|---|---|
| server → client | `task-started` | `id`, `title`, `agent` |
| server → client | `task-progress` | `id`, `message` |
| server → client | `task-completed` | `id`, `summary` |
| server → client | `task-failed` | `id`, `message` |
| client → server | `cancel-task` | `id` |

`approval-request` acquisisce i campi `agent` e `taskId`.

### Implementazione in Spring AI

- Ogni specialista è un `ChatClient` creato da `ProviderRouter` con il proprio system prompt e i propri tool, senza la memoria della conversazione (ha una memoria per task).
- I tool di delega si creano a runtime, uno per specialista in configurazione (`jarvis.agents.*`), come `ToolCallback` programmatici: aggiungere uno specialista non richiede codice. L'API esatta va verificata su Spring AI 2.0.
- `AgentRegistry` carica le specifiche e costruisce gli specialisti; `TaskService` gestisce coda, esecuzione, eventi e annullamento.

## Persistenza

- PostgreSQL 18 con pgvector (`compose.yaml`, porta 5433 su loopback). `./mvnw spring-boot:run` avvia il container grazie al supporto Docker Compose di Spring Boot; con `java -jar` va avviato prima con `docker compose up -d`.
- Lo schema è in `schema.sql`, idempotente ed eseguito a ogni avvio. Alla prima modifica incompatibile si passa a Flyway.
- L'estensione `vector` è già attiva: servirà alla memoria a lungo termine e al RAG (Fase 5), con un modello di embedding OpenAI o Ollama, perché Anthropic non ne offre.
- I test d'integrazione usano Testcontainers con la stessa immagine.

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
6. **Orchestrazione: delega come tool.** Gli specialisti si presentano all'orchestratore come tool, partono con un contesto pulito, delegano su un solo livello, e chi legge contenuti non fidati non può scrivere. I lavori lunghi girano come task in background, così la conversazione vocale non si blocca.
7. **PostgreSQL + pgvector da subito.** Un solo database per conversazioni, task (Fase 2) e vettori (Fase 5), invece di un database su file da migrare più avanti.
8. **Voce locale di default: Whisper `base` + Piper Paola**, misurati su CPU da portatile (Core Ultra 7 165U, senza GPU dedicata). Su una frase di 2,5 s: Whisper `base` circa 2 s, `small` circa 5,5 s; Piper circa 0,2 s per frase, Kokoro circa 5 volte più lento. Con una GPU conviene `small` o `large-v3-turbo` e l'immagine `latest-cuda`.
9. **Haiku per i turni parlati** (`jarvis.providers.voice-models`). Misurato: circa 2 s al primo audio contro 4–20 s di Sonnet, che resta il modello dei turni scritti. Vale anche quando la voce la produce il browser.

## Roadmap

- **Fase 0 — questo scheletro.** Chat multi-provider in streaming, push-to-talk, TTS per frase, orb animato, tool d'esempio con approvazione.
- **Fase 1 — base solida (fatta).** Build verde, Maven wrapper, test, gestione errori e riconnessione, persistenza su PostgreSQL + pgvector (i task arriveranno con la Fase 2), log strutturati (file JSON ECS in `~/.jarvis/logs/`).
- **Fase 2 — task e orchestrazione.** `TaskService` con task in background, eventi e pannello attività nell'interfaccia; approvazioni per conversazione; `AgentRegistry` e delega come tool; primo specialista in sola lettura (`researcher`); budget e annullamento.
- **Fase 3 — tool reali e nuovi specialisti.** Client MCP (filesystem, calendario, GitHub) con allowlist per specialista; specialisti `documents`, `organizer` e `coder`; azioni desktop (aprire app e URL).
- **Fase 4 — voce naturale.** VAD nel browser e barge-in automatico, wake word, STT in streaming, valutazione di un'API realtime speech-to-speech. STT/TTS locali e Web Speech API sono già disponibili.
- **Fase 5 — app desktop.** Shell Tauri con il backend come sidecar, tray, avvio automatico; memoria a lungo termine e RAG sui documenti (pgvector).
- **Fase 6 — autonomia controllata.** Routine pianificate (briefing del mattino), agenti esterni via A2A, specialista valutatore che verifica i risultati, eventuale delega su più livelli.
