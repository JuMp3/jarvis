# Jarvis

Assistente AI personale con voce: backend Java (Spring Boot 4.1 + Spring AI 2.0), provider Claude, OpenAI e Ollama, interfaccia web con un'animazione che reagisce mentre Jarvis ascolta, pensa e parla.

## Avvio rapido

Requisiti: Java 25+, Docker (per PostgreSQL con pgvector), un browser recente. Maven non serve: c'è il wrapper.

```bash
cp jarvis-local.yml.example jarvis-local.yml   # inserisci le tue chiavi (oppure un file .env)
./mvnw spring-boot:run                          # avvia anche Postgres da compose.yaml
```

Apri http://localhost:8080: al primo avvio un wizard chiede chiavi, voce, cartelle e account, e li salva nel database (i segreti cifrati, con la chiave in `~/.jarvis/master.key`). Puoi cambiarli quando vuoi dall'icona Impostazioni. Poi tieni premuto il microfono (o la barra spaziatrice) e parla.

`.env` e `jarvis-local.yml` restano comodi in sviluppo; le impostazioni salvate hanno la precedenza su di loro, le variabili d'ambiente su tutto.

In alternativa alle chiavi in `jarvis-local.yml`:

```bash
export ANTHROPIC_API_KEY=...
export OPENAI_API_KEY=...          # serve anche per la voce
export JARVIS_PROVIDERS=anthropic,openai,ollama
./mvnw spring-boot:run
```

### Voce

Trascrizione (`jarvis.voice.stt`) e sintesi (`jarvis.voice.tts`) si scelgono in `application.yml`, indipendentemente:

| Valore | Motore | Note |
|---|---|---|
| `local` (default) | Speaches in Docker: Whisper `base` + Piper (voce Paola) | Gratis e privato. Parte con `compose.yaml`; al primo avvio scarica i modelli |
| `openai` | API audio OpenAI | Qualità migliore, serve `OPENAI_API_KEY` con credito |
| `browser` | Web Speech API | Gratis, ma il riconoscimento manda l'audio a Google/Microsoft e l'orb non segue la voce reale |
| `off` | — | Solo testo |

Su reti aziendali che ispezionano il TLS (es. Netskope) Speaches non riesce a scaricare i modelli: metti un bundle di CA in `.certs/` (ignorata da git) e aggiungi `SPEACHES_CA_BUNDLE=/certs/<file>` alla `.env`.

## Cosa c'è

- Chat in streaming con più provider, selezionabili dall'interfaccia, con memoria condivisa salvata su PostgreSQL (sopravvive ai riavvii).
- Push-to-talk: trascrizione, risposta e sintesi vocale frase per frase.
- Interruzione: premi il microfono mentre Jarvis parla e si ferma.
- Ciclo agente con tool d'esempio; le azioni che modificano qualcosa chiedono la tua approvazione.
- Programmatore: su un repository delle cartelle consentite Jarvis fa lavorare Claude Code. Prima ti propone un piano, poi, se approvi, modifica il codice su un branch nuovo, lancia build e test e fa commit; il push lo fa solo se glielo autorizzi alla fine. Ha un tetto di spesa per task.
- Documenti: uno specialista legge, cerca e riassume i file (testo, PDF, Word) delle cartelle che indichi in configurazione.
- GitHub e altri servizi via MCP: si collegano in configurazione; la lettura la fanno gli specialisti, ogni azione (issue, PR, email) la fa Jarvis dopo la tua conferma.
- Desktop: Jarvis apre link, cartelle e i programmi del catalogo, sempre dopo la tua conferma.
- Squadra di agenti: Jarvis affida ricerche e testi lunghi a specialisti che lavorano in parallelo in background; li vedi orbitare intorno alla lampada e nel pannello Squadra, e Jarvis ti riferisce i risultati.
- Ascolto solo su `127.0.0.1` e WebSocket accettati solo dalla stessa origine.

Architettura, protocollo e roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
