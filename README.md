# Jarvis

Assistente AI personale con voce: backend Java (Spring Boot 4.1 + Spring AI 2.0), provider Claude, OpenAI e Ollama, interfaccia web con un'animazione che reagisce mentre Jarvis ascolta, pensa e parla.

## Avvio rapido

Requisiti: Java 25+, Docker (per PostgreSQL con pgvector), un browser recente. Maven non serve: c'è il wrapper.

```bash
./mvnw spring-boot:run                          # avvia anche Postgres da compose.yaml
```

Apri http://localhost:8080: al primo avvio un wizard chiede chiavi, voce, cartelle e account, e li salva nel database (i segreti cifrati, con la chiave in `~/.jarvis/master.key`). Puoi cambiarli quando vuoi dall'icona Impostazioni. Poi tieni premuto il microfono (o la barra spaziatrice) e parla.

Chiavi, fornitori, voce, cartelle e posta stanno solo nel database: niente chiavi in `application.yml`, in `.env` o nelle variabili d'ambiente. `jarvis-local.yml` (da `jarvis-local.yml.example`) e `.env` servono solo in sviluppo, per ciò che non è nelle Impostazioni (database, porta, modelli, cartella della voce locale) e per `compose.yaml`.

### Voce

Trascrizione e sintesi si scelgono in Impostazioni → Voce, indipendentemente:

| Valore | Motore | Note |
|---|---|---|
| `local` (default) | Sul tuo PC: whisper.cpp (Whisper `small` o `base`) + Piper (voce Paola), avviati da Jarvis | Gratis e privato, niente Docker. Binari e modelli: `desktop/prepare-voice.ps1`, poi `JARVIS_VOICE_HOME` (in `.env`) verso `desktop/src-tauri/resources/voice` |
| `openai` | API audio OpenAI | Qualità migliore, serve la chiave OpenAI (Impostazioni → Modelli) con credito |
| `browser` | Web Speech API | Gratis, ma il riconoscimento manda l'audio a Google/Microsoft, non funziona nell'app desktop e l'orb non segue la voce reale |
| `off` | — | Solo testo |

## App desktop: come rigenerarla

L'app desktop (`desktop/`, Tauri 2) contiene il backend, un Java ridotto e PostgreSQL con pgvector: per l'utente non serve né Java né Docker. Quando cambia il sorgente va ricostruita la parte toccata. Oggi i passi sono provati su **Windows**; macOS e Linux sono da completare (vedi "Multipiattaforma" in `docs/ARCHITECTURE.md`).

**Prerequisiti, una volta sola (Windows):** JDK 25, Rust (`rustup`, toolchain MSVC), Node.js 20+, Visual Studio Build Tools con gli strumenti C++ (per compilare pgvector), WebView2 (già presente in Windows 11). Poi `cd desktop && npm install`.

| Cosa è cambiato | Cosa rifare |
|---|---|
| Codice Java, pagina web (`src/main/...`), dipendenze Maven | `cd desktop && npm run backend -- -JavaHome <JDK 25>`: jar e Java ridotto in `src-tauri/resources/backend` |
| Versione di PostgreSQL o di pgvector | `powershell -ExecutionPolicy Bypass -File desktop/prepare-postgres.ps1` (parametri `-PostgresVersion`, `-PgvectorVersion`): circa un minuto, i download restano in `desktop/.cache` |
| Shell (`desktop/src-tauri/src`, `tauri.conf.json`, icone) | niente di più: la ricompila il passo dopo |

Poi, a seconda di cosa serve:

```bash
# Prova in sviluppo: eseguibile non ottimizzato, con le risorse accanto
cd desktop/src-tauri && cargo build && ./target/debug/jarvis-desktop.exe

# Shell collegata al backend di sviluppo (./mvnw spring-boot:run su 8080), senza ricostruire il backend incluso
JARVIS_BACKEND_URL=http://localhost:8080 ./target/debug/jarvis-desktop.exe

# Rilascio: eseguibile ottimizzato e installer
cd desktop && npx tauri build
#   → src-tauri/target/release/jarvis-desktop.exe
#   → src-tauri/target/release/bundle/nsis/Jarvis_<versione>_x64-setup.exe   (circa 180 MB)
```

La versione dell'app sta in `desktop/src-tauri/tauri.conf.json` e `Cargo.toml`: aumentala a ogni rilascio. La prima build di rilascio richiede circa 10 minuti (compilazione ottimizzata di Tauri), quelle dopo molto meno. I dati dell'utente non sono nell'installer: stanno in `~/.jarvis` (database in `pgdata`, chiave master, log) e restano tra un aggiornamento e l'altro.

**Altri sistemi operativi (da completare):** `npx tauri build` produce `.dmg` su macOS e AppImage o `.deb` su Linux, ma va eseguito su quel sistema e servono le versioni bash degli script `prepare-*` e i binari di PostgreSQL per quel sistema.

## Cosa c'è

- Chat in streaming con più provider, selezionabili dall'interfaccia, con memoria condivisa salvata su PostgreSQL (sopravvive ai riavvii).
- Push-to-talk: trascrizione, risposta e sintesi vocale frase per frase.
- Mani libere: Jarvis capisce da solo quando inizi e finisci di parlare, e si interrompe se parli mentre risponde. Con "Solo se dico «Jarvis»" ascolta solo le frasi rivolte a lui. Con le casse funziona meglio a volume moderato; con le cuffie sempre.
- Interruzione: premi il microfono mentre Jarvis parla e si ferma.
- Ciclo agente con tool d'esempio; le azioni che modificano qualcosa chiedono la tua approvazione.
- Programmatore: su un repository delle cartelle consentite Jarvis fa lavorare Claude Code. Prima ti propone un piano, poi, se approvi, modifica il codice su un branch nuovo, lancia build e test e fa commit; il push lo fa solo se glielo autorizzi alla fine. Ha un tetto di spesa per task.
- Posta e calendario: Outlook.com/Hotmail e Microsoft 365 (accesso Microsoft con un codice), oppure qualsiasi casella IMAP come Gmail, iCloud, Yahoo o Libero (con una password per le app). L'Organizer legge e riassume; gli invii li fa Jarvis dopo la tua conferma.
- Documenti: uno specialista legge, cerca e riassume i file (testo, PDF, Word) delle cartelle che indichi in configurazione.
- GitHub e altri servizi via MCP: si collegano in configurazione; la lettura la fanno gli specialisti, ogni azione (issue, PR, email) la fa Jarvis dopo la tua conferma.
- Desktop: Jarvis apre link, cartelle e i programmi del catalogo, sempre dopo la tua conferma.
- Squadra di agenti: Jarvis affida ricerche e testi lunghi a specialisti che lavorano in parallelo in background; li vedi orbitare intorno alla lampada e nel pannello Squadra, e Jarvis ti riferisce i risultati.
- Ascolto solo su `127.0.0.1` e WebSocket accettati solo dalla stessa origine.

Architettura, protocollo e roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
