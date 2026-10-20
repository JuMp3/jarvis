# Jarvis

Assistente AI personale con voce: backend Java (Spring Boot 4.1 + Spring AI 2.0), provider Claude, OpenAI e Ollama, interfaccia web con un'animazione che reagisce mentre Jarvis ascolta, pensa e parla.

## Avvio rapido

Requisiti: Java 21+, Maven 3.9+, un browser recente.

```bash
cp jarvis-local.yml.example jarvis-local.yml   # inserisci le tue chiavi
mvn spring-boot:run
```

Apri http://localhost:8080, tieni premuto il microfono (o la barra spaziatrice) e parla.

In alternativa alle chiavi in `jarvis-local.yml`:

```bash
export ANTHROPIC_API_KEY=...
export OPENAI_API_KEY=...          # serve anche per la voce
export JARVIS_PROVIDERS=anthropic,openai,ollama
mvn spring-boot:run
```

Senza `OPENAI_API_KEY` Jarvis funziona in sola chat testuale.

## Cosa c'è

- Chat in streaming con più provider, selezionabili dall'interfaccia, con memoria condivisa.
- Push-to-talk: trascrizione, risposta e sintesi vocale frase per frase.
- Interruzione: premi il microfono mentre Jarvis parla e si ferma.
- Ciclo agente con tool d'esempio; le azioni che modificano qualcosa chiedono la tua approvazione.
- Ascolto solo su `127.0.0.1` e WebSocket accettati solo dalla stessa origine.

Architettura, protocollo e roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
