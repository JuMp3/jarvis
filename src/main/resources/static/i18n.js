// Texts of the page in every language Jarvis speaks (SettingsCatalog.LANGUAGES). The server says which one in hello;
// until then the page uses the last one it saw, or the browser's. Pure logic plus a few DOM helpers.
// t('key', { name: value }) fills {name}; a key missing in a language falls back to Italian, then to the key itself.

export const messages = {
  it: {
    'header.model': 'Modello',
    'header.speak': 'Rispondi a voce',
    'header.handsfree': 'Mani libere',
    'header.wake': 'Solo se dico «{word}»',
    'header.clear': 'Cancella la conversazione',
    'header.settings': 'Impostazioni',
    'stage.presence': 'Stato di Jarvis',
    'stage.conversation': 'Conversazione',
    'stage.empty': 'Tieni premuto il microfono e parla, oppure scrivi qui sotto.',
    'composer.label': 'Messaggio per Jarvis',
    'composer.placeholder': 'Scrivi a Jarvis',
    'composer.send': 'Invia',
    'composer.mic': 'Tieni premuto per parlare',

    'status.connecting': 'Connessione in corso…',
    'status.disconnected': 'Connessione persa. Riprovo…',
    'status.idle': 'Tieni premuto il microfono o la barra spaziatrice per parlare',
    'status.idleTouch': 'Tieni premuto il microfono per parlare',
    'status.listening': 'Ti ascolto…',
    'status.thinking': 'Sto pensando…',
    'status.speaking': 'Sto rispondendo. Premi il microfono per interrompermi',
    'status.handsfree': 'Mani libere: parla quando vuoi',
    'status.handsfreeWake': 'Mani libere: di\' «{word}» e poi la tua richiesta',

    'turn.ignored': 'Non era per me: «{text}»',
    'turn.connectionLost': 'Connessione persa: la risposta in corso è stata interrotta.',
    'turn.settingsApplied': 'Impostazioni applicate.',
    'history.clearConfirm': 'Cancellare la conversazione dallo schermo? Jarvis riparte da zero; quanto vi siete detti resta nel suo archivio.',

    'voice.playbackFailed': 'Non riesco a riprodurre l\'audio della risposta.',
    'voice.noMicrophone': 'Microfono non disponibile: consenti l\'accesso al microfono nelle impostazioni del browser.',
    'voice.noRecognition': 'Questo browser non supporta il riconoscimento vocale: scegli «Sul tuo PC» o «OpenAI» in Impostazioni → Voce.',
    'voice.noRecognitionDesktop': 'Nell\'app desktop il riconoscimento vocale del browser non è disponibile: scegli «Sul tuo PC» o «OpenAI» in Impostazioni → Voce.',
    'voice.recognitionNetwork': 'Il riconoscimento vocale del browser usa un servizio online che non risponde (rete o proxy aziendale): prova «Sul tuo PC» in Impostazioni → Voce.',
    'voice.recognitionFailed': 'Riconoscimento vocale del browser non riuscito ({error}).',
    'voice.nothingHeard': 'Non ho sentito nulla. Riprova parlando più vicino al microfono.',
    'voice.tooShort': 'Tieni premuto mentre parli, poi rilascia.',
    'voice.off': 'Voce non attiva: scegli chi trascrive la tua voce in Impostazioni → Voce',

    'card.pending': 'Preparo la scheda…',
    'weather.title': 'Meteo',
    'weather.feels': 'percepita {value}',
    'weather.humidity': 'umidità {value}%',
    'weather.wind': 'vento {value} km/h',

    'provider.ollama': 'Ollama (locale)',
    'agent.researcher': 'Ricercatore',
    'agent.writer': 'Scrittore',
    'agent.coder': 'Programmatore',
    'agent.documents': 'Documenti',
    'agent.organizer': 'Organizer',
    'agent.github': 'GitHub',

    'team.title': 'Squadra',
    'team.close': 'Chiudi il pannello della squadra',
    'team.intro': 'I lavori che Jarvis ha affidato agli specialisti. Girano in parallelo mentre continuate a parlare.',
    'team.clear': 'Togli i lavori finiti',
    'team.clearConfirm': 'Togliere dalla Squadra i lavori finiti? Non li vedrai più qui.',
    'team.empty': 'Nessun lavoro per ora. Chiedi a Jarvis una ricerca o un testo lungo: lo affiderà a uno specialista.',
    'team.idle': 'Squadra a riposo',
    'team.waitingOne': '1 aspetta la tua approvazione',
    'team.waitingMany': '{count} aspettano la tua approvazione',
    'team.runningOne': '1 agente al lavoro',
    'team.runningMany': '{count} agenti al lavoro',
    'team.queued': '{count} in coda',
    'announce.running': '{who} al lavoro: {title}',
    'announce.completed': '{who} ha finito: {title}',
    'announce.failed': '{who} non è riuscito: {title}',

    'task.queued': 'in coda',
    'task.running': 'al lavoro da {time}',
    'task.waiting': 'aspetta la tua approvazione',
    'task.completed': 'fatto in {time}',
    'task.failed': 'non riuscito',
    'task.cancelled': 'fermato',
    'task.interrupted': 'interrotto dal riavvio',
    'task.stop': 'Ferma',
    'task.stopLabel': 'Ferma il lavoro: {title}',
    'task.resultAndInstructions': 'Risultato e istruzioni',
    'task.result': 'Risultato',
    'task.reason': 'Motivo',
    'task.instructions': 'Istruzioni',
    'task.repository': 'Repository',
    'task.cost': 'Costo',
    'task.tokens': 'Token',
    'task.tokenCounts': '{input} in ingresso, {output} in uscita',

    'approval.title': '{who} chiede il permesso',
    'approval.deny': 'Nega',
    'approval.allow': 'Consenti',

    'settings.title': 'Impostazioni',
    'settings.wizardTitle': 'Configuriamo Jarvis',
    'settings.wizardIntro': 'Passo {step} di {total}. Puoi cambiare tutto più tardi da Impostazioni.',
    'settings.intro': 'Le modifiche si applicano con un breve riavvio di Jarvis.',
    'settings.sections': 'Sezioni delle impostazioni',
    'settings.later': 'Più tardi',
    'settings.close': 'Chiudi',
    'settings.back': 'Indietro',
    'settings.next': 'Avanti',
    'settings.save': 'Salva e riavvia',
    'settings.saving': 'Salvo…',
    'settings.saved': 'Salvate. Riavvio Jarvis per applicarle…',
    'settings.verify': 'Verifica',
    'settings.verifying': 'Verifico…',
    'settings.origin.settings': 'nelle impostazioni',
    'settings.origin.environment': 'da una variabile d\'ambiente, che ha la precedenza',
    'settings.origin.file': 'da un file di configurazione (.env o application.yml)',
    'settings.secret.placeholder': 'Incolla qui la chiave',
    'settings.secret.set': '✓ Impostata {origin}. Lascia vuoto il campo per non cambiarla.',
    'settings.secret.unset': 'Non impostata.',
    'settings.secret.show': 'Mostra la chiave',
    'settings.secret.hide': 'Nascondi la chiave',
    'settings.secret.remove': 'Rimuovi',
    'settings.secret.undo': 'Annulla',
    'settings.secret.removing': 'Verrà rimossa al salvataggio.',
    'settings.apps.name': 'Nome',
    'settings.apps.program': 'Programma',
    'settings.apps.arguments': 'Argomenti',
    'settings.apps.optional': 'facoltativi',
    'settings.apps.remove': 'Togli',
    'settings.apps.add': 'Aggiungi un\'app',
    'settings.account.login': 'Collega l\'account Microsoft',
    'settings.account.verify': 'Ho fatto l\'accesso',
    'settings.account.asking': 'Chiedo il codice a Microsoft…',
    'settings.routines.name': 'Nome della routine',
    'settings.routines.namePlaceholder': 'Briefing del mattino',
    'settings.routines.time': 'Ora',
    'settings.routines.days': 'Giorni',
    'settings.routines.enabled': 'Attiva',
    'settings.routines.instructions': 'Istruzioni',
    'settings.routines.instructionsPlaceholder': 'Cosa deve fare Jarvis, per esempio: meteo di oggi a Milano, email importanti arrivate da ieri, impegni di oggi.',
    'settings.routines.add': 'Aggiungi una routine',
    'days.mon': 'lun',
    'days.tue': 'mar',
    'days.wed': 'mer',
    'days.thu': 'gio',
    'days.fri': 'ven',
    'days.sat': 'sab',
    'days.sun': 'dom',
    'agent.routine': 'Routine',
    'routine.notification': 'Jarvis · {title}',
  },
  en: {
    'header.model': 'Model',
    'header.speak': 'Answer aloud',
    'header.handsfree': 'Hands-free',
    'header.wake': 'Only when I say “{word}”',
    'header.clear': 'Clear the conversation',
    'header.settings': 'Settings',
    'stage.presence': 'Jarvis status',
    'stage.conversation': 'Conversation',
    'stage.empty': 'Hold the microphone and speak, or type below.',
    'composer.label': 'Message for Jarvis',
    'composer.placeholder': 'Write to Jarvis',
    'composer.send': 'Send',
    'composer.mic': 'Hold to speak',

    'status.connecting': 'Connecting…',
    'status.disconnected': 'Connection lost. Retrying…',
    'status.idle': 'Hold the microphone or the space bar to speak',
    'status.idleTouch': 'Hold the microphone to speak',
    'status.listening': 'Listening…',
    'status.thinking': 'Thinking…',
    'status.speaking': 'Answering. Press the microphone to interrupt me',
    'status.handsfree': 'Hands-free: speak whenever you like',
    'status.handsfreeWake': 'Hands-free: say “{word}” and then your request',

    'turn.ignored': 'Not for me: “{text}”',
    'turn.connectionLost': 'Connection lost: the answer in progress was interrupted.',
    'turn.settingsApplied': 'Settings applied.',
    'history.clearConfirm': 'Clear the conversation from the screen? Jarvis starts over; what you said stays in his archive.',

    'voice.playbackFailed': 'I can\'t play the audio of the answer.',
    'voice.noMicrophone': 'Microphone not available: allow access to the microphone in the browser settings.',
    'voice.noRecognition': 'This browser does not support speech recognition: choose “On your PC” or “OpenAI” in Settings → Voice.',
    'voice.noRecognitionDesktop': 'The browser\'s speech recognition is not available in the desktop app: choose “On your PC” or “OpenAI” in Settings → Voice.',
    'voice.recognitionNetwork': 'The browser\'s speech recognition uses an online service that does not answer (network or company proxy): try “On your PC” in Settings → Voice.',
    'voice.recognitionFailed': 'The browser\'s speech recognition failed ({error}).',
    'voice.nothingHeard': 'I didn\'t hear anything. Try again closer to the microphone.',
    'voice.tooShort': 'Hold while you speak, then release.',
    'voice.off': 'Voice is off: choose who transcribes your voice in Settings → Voice',

    'card.pending': 'Preparing the card…',
    'weather.title': 'Weather',
    'weather.feels': 'feels like {value}',
    'weather.humidity': 'humidity {value}%',
    'weather.wind': 'wind {value} km/h',

    'provider.ollama': 'Ollama (local)',
    'agent.researcher': 'Researcher',
    'agent.writer': 'Writer',
    'agent.coder': 'Programmer',
    'agent.documents': 'Documents',
    'agent.organizer': 'Organizer',
    'agent.github': 'GitHub',

    'team.title': 'Team',
    'team.close': 'Close the team panel',
    'team.intro': 'The work Jarvis gave to the specialists. It runs in parallel while you keep talking.',
    'team.clear': 'Remove finished work',
    'team.clearConfirm': 'Remove finished work from the Team? You will not see it here anymore.',
    'team.empty': 'No work yet. Ask Jarvis for some research or a long text: he will give it to a specialist.',
    'team.idle': 'Team at rest',
    'team.waitingOne': '1 waiting for your approval',
    'team.waitingMany': '{count} waiting for your approval',
    'team.runningOne': '1 agent at work',
    'team.runningMany': '{count} agents at work',
    'team.queued': '{count} queued',
    'announce.running': '{who} at work: {title}',
    'announce.completed': '{who} finished: {title}',
    'announce.failed': '{who} did not succeed: {title}',

    'task.queued': 'queued',
    'task.running': 'at work for {time}',
    'task.waiting': 'waiting for your approval',
    'task.completed': 'done in {time}',
    'task.failed': 'failed',
    'task.cancelled': 'stopped',
    'task.interrupted': 'interrupted by the restart',
    'task.stop': 'Stop',
    'task.stopLabel': 'Stop the work: {title}',
    'task.resultAndInstructions': 'Result and instructions',
    'task.result': 'Result',
    'task.reason': 'Reason',
    'task.instructions': 'Instructions',
    'task.repository': 'Repository',
    'task.cost': 'Cost',
    'task.tokens': 'Tokens',
    'task.tokenCounts': '{input} in, {output} out',

    'approval.title': '{who} asks for permission',
    'approval.deny': 'Deny',
    'approval.allow': 'Allow',

    'settings.title': 'Settings',
    'settings.wizardTitle': 'Let\'s set up Jarvis',
    'settings.wizardIntro': 'Step {step} of {total}. You can change everything later in Settings.',
    'settings.intro': 'Changes apply with a short restart of Jarvis.',
    'settings.sections': 'Settings sections',
    'settings.later': 'Later',
    'settings.close': 'Close',
    'settings.back': 'Back',
    'settings.next': 'Next',
    'settings.save': 'Save and restart',
    'settings.saving': 'Saving…',
    'settings.saved': 'Saved. Restarting Jarvis to apply them…',
    'settings.verify': 'Check',
    'settings.verifying': 'Checking…',
    'settings.origin.settings': 'in the settings',
    'settings.origin.environment': 'by an environment variable, which takes precedence',
    'settings.origin.file': 'by a configuration file (.env or application.yml)',
    'settings.secret.placeholder': 'Paste the key here',
    'settings.secret.set': '✓ Set {origin}. Leave the field empty to keep it.',
    'settings.secret.unset': 'Not set.',
    'settings.secret.show': 'Show the key',
    'settings.secret.hide': 'Hide the key',
    'settings.secret.remove': 'Remove',
    'settings.secret.undo': 'Undo',
    'settings.secret.removing': 'It will be removed when you save.',
    'settings.apps.name': 'Name',
    'settings.apps.program': 'Program',
    'settings.apps.arguments': 'Arguments',
    'settings.apps.optional': 'optional',
    'settings.apps.remove': 'Remove',
    'settings.apps.add': 'Add an app',
    'settings.account.login': 'Connect the Microsoft account',
    'settings.account.verify': 'I have signed in',
    'settings.account.asking': 'Asking Microsoft for the code…',
    'settings.routines.name': 'Routine name',
    'settings.routines.namePlaceholder': 'Morning briefing',
    'settings.routines.time': 'Time',
    'settings.routines.days': 'Days',
    'settings.routines.enabled': 'On',
    'settings.routines.instructions': 'Instructions',
    'settings.routines.instructionsPlaceholder': 'What Jarvis should do, for example: today\'s weather in London, important emails since yesterday, today\'s events.',
    'settings.routines.add': 'Add a routine',
    'days.mon': 'Mon',
    'days.tue': 'Tue',
    'days.wed': 'Wed',
    'days.thu': 'Thu',
    'days.fri': 'Fri',
    'days.sat': 'Sat',
    'days.sun': 'Sun',
    'agent.routine': 'Routine',
    'routine.notification': 'Jarvis · {title}',
  },
};

export const LANGUAGES = Object.keys(messages);

const supported = (code) => (LANGUAGES.includes(code) ? code : 'en');

/** The language of the last hello, else the browser's: the page speaks it before the server answers. */
function initialLanguage() {
  try {
    const saved = globalThis.localStorage?.getItem('jarvis.language');
    if (saved) return supported(saved);
  } catch { /* storage disabled */ }
  return supported((globalThis.navigator?.language ?? 'it').slice(0, 2).toLowerCase());
}

let current = initialLanguage();
const listeners = [];

export const language = () => current;

export function t(key, params = {}) {
  const text = messages[current][key] ?? messages.it[key] ?? key;
  return text.replace(/\{(\w+)\}/g, (match, name) => (name in params ? String(params[name]) : match));
}

/** Changes the language of the page; the listeners redraw what they wrote with t(). */
export function setLanguage(code) {
  const next = supported(code);
  try {
    globalThis.localStorage?.setItem('jarvis.language', next);
  } catch { /* storage disabled */ }
  if (next === current) return;
  current = next;
  listeners.forEach((listener) => listener(current));
}

export function onLanguageChange(listener) {
  listeners.push(listener);
}

/**
 * The fixed texts of the page: data-i18n="key" sets the text, data-i18n-attr="aria-label:key title:key" the
 * attributes.
 */
export function translatePage(root = document) {
  root.documentElement && (root.documentElement.lang = current);
  for (const node of root.querySelectorAll('[data-i18n]')) node.textContent = t(node.dataset.i18n);
  for (const node of root.querySelectorAll('[data-i18n-attr]')) {
    for (const pair of node.dataset.i18nAttr.split(/\s+/)) {
      const [attribute, key] = pair.split(':');
      node.setAttribute(attribute, t(key));
    }
  }
}
