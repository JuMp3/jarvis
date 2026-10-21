# Jarvis

Assistente AI personale con voce: backend Java (Spring Boot 4.1 + Spring AI 2.0), provider Claude, OpenAI e Ollama, interfaccia web con un'animazione che reagisce mentre Jarvis ascolta, pensa e parla.

## Avvio rapido

Requisiti: Java 25+, Docker (per PostgreSQL con pgvector), un browser recente. Maven non serve: c'è il wrapper.

```bash
cp jarvis-local.yml.example jarvis-local.yml   # inserisci le tue chiavi (oppure un file .env)
./mvnw spring-boot:run                          # avvia anche Postgres da compose.yaml
```

Apri http://localhost:8080, tieni premuto il microfono (o la barra spaziatrice) e parla.

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
- Squadra di agenti: Jarvis affida ricerche e testi lunghi a specialisti che lavorano in parallelo in background; li vedi orbitare intorno alla lampada e nel pannello Squadra, e Jarvis ti riferisce i risultati.
- Ascolto solo su `127.0.0.1` e WebSocket accettati solo dalla stessa origine.

Architettura, protocollo e roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
