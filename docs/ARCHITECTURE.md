# Jarvis — Architettura

Assistente AI personale con backend Java (Spring Boot 4.1 + Spring AI 2.0), più provider cloud e locali, interazione vocale e un'interfaccia animata che reagisce alla voce.

## Vista d'insieme

```
┌──────────────────────────── Frontend (browser, poi Tauri) ────────────────────────────┐
│  orb.js      animazione canvas: idle · listening · thinking · speaking                 │
│  app.js      WebSocket client · microfono (MediaRecorder) · coda di riproduzione audio │
│              AnalyserNode sul microfono e sull'audio in uscita → livello per l'orb     │
└───────────────────────────────┬────────────────────────────────────────────────────────┘
                                │  WebSocket /ws
                                │  testo: messaggi JSON   ·   binario: audio registrato
┌───────────────────────────────▼──────────────── Backend (Spring Boot) ─────────────────┐
│  ws/      JarvisWebSocketHandler · SessionRegistry · TurnService (un turno = una risposta)│
│  agent/   AgentService (ChatClient + memoria + tool) · ApprovalService · tools/          │
│  llm/     ProviderRouter → un ChatClient per provider (Anthropic · OpenAI · Ollama)     │
│  voice/   SpeechToText · TextToSpeech (OpenAI via HTTP) · SentenceChunker               │
│  config/  JarvisProperties (prefisso "jarvis")                                          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

Il frontend gestisce tutto ciò che è audio e grafica; il backend è l'orchestratore. Java non tocca mai il microfono o le casse: è più semplice, funziona uguale nel browser e in Tauri, e lo stesso segnale audio pilota l'animazione.

## Componenti

| Componente | Responsabilità |
|---|---|
| `ProviderRouter` | Trova i `ChatModel` configurati da Spring AI, tiene solo i provider abilitati e crea un `ChatClient` per ciascuno con system prompt e memoria condivisa. Cambiare provider a metà conversazione mantiene la cronologia. |
| `AgentService` | Un turno di conversazione: prompt utente → streaming della risposta, con memoria (`conversationId`) e tool. Il ciclo tool → risultato → passo successivo lo esegue Spring AI. |
| `ApprovalService` | I tool che modificano qualcosa chiedono conferma all'utente e attendono la risposta (con timeout: nessuna risposta = negato). |
| `tools/` | `SystemTools` (sola lettura) e `NoteTools` (scrittura in una cartella sandbox, previa approvazione). Esempi del pattern da seguire per tutti i tool futuri. |
| `SpeechToText` / `TextToSpeech` | Interfacce. Implementazione attuale: API audio OpenAI chiamate con `java.net.http`. Sostituibili con whisper.cpp / Piper in locale. |
| `SentenceChunker` | Spezza lo stream di token in frasi complete, così il TTS parte alla prima frase invece di aspettare la risposta intera. |
| `TurnService` | Collega tutto per un turno: stato → token → frasi → audio ordinato → fine. Gestisce l'annullamento (barge-in). |

## Flusso vocale

1. L'utente tiene premuto il tasto microfono (o la barra spaziatrice): il browser registra con `MediaRecorder` e l'orb va in `listening` seguendo il livello del microfono.
2. Al rilascio, l'audio (webm/opus) parte come frame **binario** sul WebSocket.
3. Il backend lo trascrive (`SpeechToText`) e rimanda `transcript`.
4. `AgentService` avvia lo streaming verso il provider selezionato; l'orb va in `thinking`.
5. Ogni token va al client (`token`) e al `SentenceChunker`.
6. Ogni frase completa va al TTS, su un esecutore a thread singolo, così l'ordine è garantito; l'audio torna come `audio` con un numero di sequenza.
7. Il client accoda e riproduce l'audio in ordine; l'orb va in `speaking` seguendo il livello dell'audio in uscita.
8. Se l'utente riprende a parlare mentre Jarvis risponde, il client ferma l'audio e invia `cancel`: il backend annulla lo stream e il TTS in corso.

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
| `hello` | `providers`, `defaultProvider`, `voiceEnabled` |
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

## Configurazione e segreti

- `application.yml` contiene solo valori di default e riferimenti a variabili d'ambiente.
- Le chiavi vanno in variabili d'ambiente oppure in `jarvis-local.yml` nella cartella da cui si avvia l'app (in `.gitignore`, importato con `spring.config.import`).
- Un provider cloud senza chiave resta configurato con un segnaposto, così l'app parte comunque; basta non includerlo in `jarvis.providers.enabled`.

## Decisioni

1. **Spring Boot 4.1 + Spring AI 2.0.** Astrazione multi-provider, tool calling, memoria come advisor e supporto MCP maturo.
2. **Audio nel browser, non in Java.** Web Audio API per cattura, riproduzione e analisi del livello; il backend riceve e invia solo byte.
3. **STT/TTS con HTTP diretto** invece delle API audio di Spring AI. Il modulo OpenAI è stato riscritto nella 2.0; tenere l'audio dietro interfacce nostre con il solo JDK riduce le dipendenze e rende facile passare a motori locali.
4. **Approvazione dentro i tool.** Funziona con il ciclo automatico di Spring AI e mette la regola di sicurezza accanto al codice che la richiede.
5. **TTS sequenziale per turno.** Ordine garantito senza complessità; la parallelizzazione con riordino lato client è un'ottimizzazione successiva (il campo `seq` è già previsto).

## Roadmap

- **Fase 0 — questo scheletro.** Chat multi-provider in streaming, push-to-talk, TTS per frase, orb animato, tool d'esempio con approvazione.
- **Fase 1 — base solida.** Build verde, Maven wrapper, test, gestione errori e riconnessione, memoria persistente (repository JDBC della chat memory), log strutturati.
- **Fase 2 — tool reali.** Client MCP (filesystem, calendario, GitHub) con allowlist dei tool esposti; azioni desktop (aprire app e URL).
- **Fase 3 — voce naturale.** VAD nel browser e barge-in automatico, wake word, STT/TTS locali dietro le stesse interfacce, valutazione di un'API realtime speech-to-speech.
- **Fase 4 — app desktop.** Shell Tauri con il backend come sidecar, tray, avvio automatico; memoria a lungo termine e RAG sui documenti (pgvector).
- **Fase 5 — autonomia controllata.** Routine pianificate (briefing del mattino), pattern multi-agente (router, orchestratore-worker, valutatore).
