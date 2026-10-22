import { Orb } from './orb.js';
import { createSettings } from './settings.js';
import { VoiceDetector } from './vad.js';
import { SpeechCapture } from './capture.js';

// Message types must match dev.jarvis.ws.Protocol and docs/ARCHITECTURE.md.

const PROVIDER_LABELS = { anthropic: 'Claude', openai: 'OpenAI', ollama: 'Ollama (locale)' };
const MIN_RECORDING_MS = 350;

const el = {
  provider: document.getElementById('provider'),
  speak: document.getElementById('speak'),
  handsfree: document.getElementById('handsfree'),
  wake: document.getElementById('wake'),
  wakeToggle: document.getElementById('wake-toggle'),
  status: document.getElementById('status'),
  log: document.getElementById('log'),
  conversation: document.querySelector('.conversation'),
  composer: document.getElementById('composer'),
  input: document.getElementById('input'),
  send: document.getElementById('send'),
  mic: document.getElementById('mic'),
  approval: document.getElementById('approval'),
  approvalText: document.getElementById('approval-text'),
  approvalTitle: document.getElementById('approval-title'),
  approvalTask: document.getElementById('approval-task'),
  teamToggle: document.getElementById('team-toggle'),
  teamSummary: document.getElementById('team-summary'),
  teamDots: document.querySelector('.team-dots'),
  teamClose: document.getElementById('team-close'),
  team: document.getElementById('team'),
  tasks: document.getElementById('tasks'),
  announcer: document.getElementById('team-announcer'),
};

// ---- audio ------------------------------------------------------------------------------

const audio = {
  ctx: null,
  micAnalyser: null,
  outAnalyser: null,
  stream: null,
  recorder: null,
  chunks: [],
  startedAt: 0,
  queue: new Map(),
  nextSeq: 0,
  current: null,
  turnDone: true,
  recognition: null,
};

// Web Speech API, used when the server sends stt/tts = "browser"
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
const browserSpeech = { pending: '', queued: 0, pulse: 0, voice: null };

function audioContext() {
  if (!audio.ctx) {
    audio.ctx = new AudioContext();
    audio.outAnalyser = audio.ctx.createAnalyser();
    audio.outAnalyser.fftSize = 512;
    audio.outAnalyser.connect(audio.ctx.destination);
  }
  if (audio.ctx.state === 'suspended') {
    audio.ctx.resume();
  }
  return audio.ctx;
}

function levelOf(analyser) {
  if (!analyser) return 0;
  const data = new Uint8Array(analyser.fftSize);
  analyser.getByteTimeDomainData(data);
  let sum = 0;
  for (const v of data) {
    const centered = (v - 128) / 128;
    sum += centered * centered;
  }
  return Math.min(1, Math.sqrt(sum / data.length) * 4);
}

function currentLevel() {
  if (ui.state === 'listening') return levelOf(audio.micAnalyser);
  if (ui.state === 'speaking' && browserSpeech.queued > 0) {
    // speechSynthesis does not expose its audio: pulse on word boundaries instead
    browserSpeech.pulse *= 0.92;
    return 0.2 + 0.5 * browserSpeech.pulse;
  }
  if (ui.state === 'speaking') return levelOf(audio.outAnalyser);
  return 0;
}

function base64ToBuffer(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function enqueueClip(seq, base64) {
  try {
    const buffer = await audioContext().decodeAudioData(base64ToBuffer(base64));
    audio.queue.set(seq, buffer);
    playNext();
  } catch (e) {
    note('Non riesco a riprodurre l\'audio della risposta.', true);
  }
}

function playNext() {
  if (audio.current) return;
  const buffer = audio.queue.get(audio.nextSeq);
  if (!buffer) {
    if (audio.turnDone && audio.queue.size === 0) setState('idle');
    return;
  }
  audio.queue.delete(audio.nextSeq);
  audio.nextSeq += 1;
  const source = audio.ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(audio.outAnalyser);
  source.onended = () => {
    audio.current = null;
    playNext();
  };
  audio.current = source;
  setState('speaking');
  source.start();
}

function stopPlayback() {
  audio.queue.clear();
  audio.nextSeq = 0;
  if (browserSpeech.queued > 0 || browserSpeech.pending) {
    browserSpeech.pending = '';
    browserSpeech.queued = 0;
    speechSynthesis.cancel();
  }
  if (audio.current) {
    audio.current.onended = null;
    try { audio.current.stop(); } catch { /* already stopped */ }
    audio.current = null;
  }
}

// ---- browser speech synthesis ------------------------------------------------------------

function browserVoice() {
  const voices = speechSynthesis.getVoices().filter((v) => v.lang.toLowerCase().startsWith(ui.language));
  return voices.find((v) => /natural|online/i.test(v.name)) ?? voices[0] ?? null;
}

function speakInBrowser(text) {
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = ui.language;
  browserSpeech.voice ??= browserVoice();
  if (browserSpeech.voice) utterance.voice = browserSpeech.voice;
  utterance.onstart = () => setState('speaking');
  utterance.onboundary = () => { browserSpeech.pulse = 1; };
  utterance.onend = utterance.onerror = () => {
    browserSpeech.queued = Math.max(0, browserSpeech.queued - 1);
    if (browserSpeech.queued === 0 && audio.turnDone) setState('idle');
  };
  browserSpeech.queued += 1;
  speechSynthesis.speak(utterance);
}

/** Speaks every complete sentence as soon as it has streamed in; the rest waits for more tokens. */
function speakStreamed(text, final = false) {
  browserSpeech.pending += text;
  const complete = final ? browserSpeech.pending : browserSpeech.pending.match(/^[\s\S]*[.!?…]\s/)?.[0];
  if (!complete) return;
  browserSpeech.pending = browserSpeech.pending.slice(complete.length);
  if (complete.trim()) speakInBrowser(complete.trim());
}

/** Starts a new turn locally: forget pending audio, interrupt Jarvis if it is still answering. */
function beginTurn() {
  const busy = ui.state === 'thinking' || ui.state === 'speaking' || audio.current;
  stopPlayback();
  audio.turnDone = false;
  if (busy) send({ type: 'cancel' });
  finishStreaming();
}

/** @param spoken whether the answer should be spoken (by the server or by the browser) */
function sendChat(text, spoken) {
  ui.spokenTurn = spoken && ui.tts !== 'off';
  send({ type: 'chat', text, provider: el.provider.value, speak: ui.spokenTurn });
  setState('thinking');
}

// ---- microphone -------------------------------------------------------------------------

/** The microphone stays open once allowed; its level feeds the orb and the hands-free detector. */
async function openMicrophone() {
  const ctx = audioContext();
  if (!audio.stream) {
    // Echo cancellation keeps Jarvis' own voice out of the microphone as much as the browser can
    audio.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    audio.micAnalyser = ctx.createAnalyser();
    audio.micAnalyser.fftSize = 512;
    audio.micSource = ctx.createMediaStreamSource(audio.stream);
    audio.micSource.connect(audio.micAnalyser);
  }
}

// ---- hands-free: the voice detector starts and stops the recording ------------------------------

const handsFree = { detector: null, timer: null, capture: null, processor: null };
const ECHO_SENSITIVITY = 2.5; // while Jarvis speaks, only a clearly louder voice interrupts him

async function startHandsFree() {
  try {
    await openMicrophone();
  } catch {
    el.handsfree.checked = false;
    note('Microfono non disponibile: consenti l\'accesso al microfono nelle impostazioni del browser.', true);
    return;
  }
  handsFree.detector = new VoiceDetector();
  // Raw samples instead of MediaRecorder, to keep the moments before the detector fires (see capture.js).
  // ScriptProcessorNode is deprecated but everywhere; AudioWorklet would need a module of its own.
  const ctx = audioContext();
  handsFree.capture = new SpeechCapture(ctx.sampleRate);
  handsFree.processor = ctx.createScriptProcessor(2048, 1, 1);
  handsFree.processor.onaudioprocess = (event) => handsFree.capture.push(event.inputBuffer.getChannelData(0).slice());
  audio.micSource.connect(handsFree.processor);
  handsFree.processor.connect(ctx.destination); // silent output: a processor only runs when connected
  // ?vad in the address shows the live levels, to tune vad.js and ECHO_SENSITIVITY on a real microphone
  const readout = new URLSearchParams(location.search).has('vad')
    && document.body.appendChild(Object.assign(document.createElement('pre'),
      { style: 'position:fixed;left:8px;bottom:8px;margin:0;font:12px monospace;color:#9fe;z-index:99' }));
  handsFree.timer = setInterval(() => {
    if (!ui.connected || el.mic.disabled || el.approval.open) return;
    const sensitivity = ui.state === 'speaking' ? ECHO_SENSITIVITY : 1;
    const level = levelOf(audio.micAnalyser);
    const event = handsFree.detector.update(level, performance.now(), sensitivity);
    if (readout) {
      const d = handsFree.detector;
      const threshold = Math.max(d.minLevel, d.floor * d.ratio) * sensitivity;
      readout.textContent = `livello ${level.toFixed(3)}  fondo ${d.floor.toFixed(3)}  soglia ${threshold.toFixed(3)}`
        + `  ${d.speaking ? 'VOCE' : '-'}  ${ui.state}${event ? '  ' + event : ''}`;
    }
    if (event === 'start' && !ui.recording) startRecording(); // interrupts Jarvis if he is answering
    if (event === 'end' && ui.recording) stopRecording();
  }, 30);
  setState(ui.state);
}

function stopHandsFree() {
  clearInterval(handsFree.timer);
  handsFree.timer = null;
  if (ui.recording) stopRecording();
  handsFree.processor?.disconnect();
  audio.micSource?.disconnect(handsFree.processor);
  handsFree.processor = handsFree.capture = null;
  setState(ui.state);
}

const wakeRequired = () => el.handsfree.checked && el.wake.checked;

function sendVoiceMode() {
  send({ type: 'voice-mode', wake: wakeRequired() });
}

/** Same rule as the server (WakeWord), for the browser's own speech recognition. */
function stripWakeWord(text) {
  const plain = (word) => word.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^a-z]/g, '');
  const target = plain(ui.wakeWord);
  const words = text.trim().split(/\s+/);
  const near = (heard) => {
    const variants = [heard, heard.replace('gi', 'j').replace('ge', 'je')];
    return heard && variants.some((v) => v === target || (Math.abs(v.length - target.length) <= 1
      && [...target].filter((c, i) => v[i] === c).length >= target.length - 1));
  };
  const at = words.slice(0, 3).findIndex((word) => near(plain(word)));
  return at < 0 ? null : words.slice(at + 1).join(' ').replace(/^[\p{P}\s]+/u, '').trim() || text;
}

el.handsfree.addEventListener('change', () => {
  el.wakeToggle.hidden = !el.handsfree.checked;
  if (el.handsfree.checked) startHandsFree();
  else stopHandsFree();
  sendVoiceMode();
});
el.wake.addEventListener('change', () => { sendVoiceMode(); setState(ui.state); });

async function startRecording() {
  if (ui.recording || el.mic.disabled) return;
  ui.recording = true;
  el.mic.classList.add('recording');
  beginTurn();
  try {
    await openMicrophone();
    if (!ui.recording) return; // released while the permission prompt was open
    if (ui.stt === 'browser') {
      startRecognition();
      setState('listening');
      return;
    }
    audio.startedAt = performance.now();
    if (handsFree.capture) {
      handsFree.capture.start();
      setState('listening');
      return;
    }
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']
      .find((type) => MediaRecorder.isTypeSupported(type));
    audio.recorder = new MediaRecorder(audio.stream, mimeType ? { mimeType } : undefined);
    audio.chunks = [];
    audio.recorder.ondataavailable = (event) => event.data.size && audio.chunks.push(event.data);
    audio.recorder.onstop = sendRecording;
    audio.recorder.start();
    setState('listening');
  } catch (e) {
    ui.recording = false;
    el.mic.classList.remove('recording');
    note('Microfono non disponibile: consenti l\'accesso al microfono nelle impostazioni del browser.', true);
    setState('idle');
  }
}

function startRecognition() {
  const recognition = new Recognition();
  recognition.lang = ui.language;
  recognition.continuous = true;
  let transcript = '';
  recognition.onresult = (event) => {
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (event.results[i].isFinal) transcript += event.results[i][0].transcript;
    }
  };
  recognition.onerror = (event) => {
    if (event.error !== 'no-speech' && event.error !== 'aborted') {
      note(`Riconoscimento vocale del browser non riuscito (${event.error}).`, true);
    }
  };
  recognition.onend = () => {
    audio.recognition = null;
    let text = transcript.trim();
    if (!text) {
      if (!el.handsfree.checked) note('Non ho sentito nulla. Riprova parlando più vicino al microfono.');
      setState('idle');
      return;
    }
    if (wakeRequired()) {
      const request = stripWakeWord(text);
      if (request === null) {
        ignored(text);
        setState('idle');
        return;
      }
      text = request;
    }
    addItem('turn-user', text);
    sendChat(text, true);
  };
  audio.recognition = recognition;
  recognition.start();
}

function stopRecording() {
  if (!ui.recording) return;
  ui.recording = false;
  el.mic.classList.remove('recording');
  if (audio.recognition) {
    audio.recognition.stop(); // the final result arrives before onend
    return;
  }
  if (handsFree.capture?.recording) {
    sendAudio(handsFree.capture.stop());
    return;
  }
  if (audio.recorder && audio.recorder.state === 'recording') {
    audio.recorder.stop();
  }
}

async function sendRecording() {
  sendAudio(await new Blob(audio.chunks, { type: audio.recorder.mimeType }).arrayBuffer());
}

function sendAudio(buffer) {
  const duration = performance.now() - audio.startedAt;
  if (duration < MIN_RECORDING_MS || buffer.byteLength === 0) {
    note('Tieni premuto mentre parli, poi rilascia.');
    setState('idle');
    return;
  }
  ui.spokenTurn = ui.tts !== 'off';
  setState('thinking');
  socket.send(buffer);
}

// ---- conversation -----------------------------------------------------------------------

let streamingItem = null;

function addItem(className, text) {
  const item = document.createElement('li');
  item.className = className;
  item.textContent = text; // never innerHTML: model output is untrusted
  el.log.append(item);
  el.conversation.scrollTop = el.conversation.scrollHeight;
  return item;
}

function note(text, isError = false) {
  addItem(isError ? 'turn-note error' : 'turn-note', text);
}

function appendToken(text) {
  if (!streamingItem) {
    streamingItem = addItem('turn-jarvis streaming', '');
  }
  streamingItem.textContent += text;
  el.conversation.scrollTop = el.conversation.scrollHeight;
}

/** Renders **bold** and http(s) links through the DOM, never innerHTML: model and agent output is untrusted. */
function richText(node, text) {
  node.replaceChildren();
  let last = 0;
  for (const match of text.matchAll(/\*\*(.+?)\*\*|(https?:\/\/[^\s)\]>"]+)/g)) {
    node.append(text.slice(last, match.index));
    if (match[1]) {
      node.append(element('strong', null, match[1]));
    } else {
      const link = element('a', null, match[2]);
      link.href = match[2];
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      node.append(link);
    }
    last = match.index + match[0].length;
  }
  node.append(text.slice(last));
  return node;
}

function finishStreaming() {
  if (streamingItem) {
    richText(streamingItem, streamingItem.textContent);
    streamingItem.classList.remove('streaming');
    streamingItem = null;
  }
}

// ---- state ------------------------------------------------------------------------------

const ui = {
  state: 'idle', recording: false, connected: false,
  stt: 'off', tts: 'off', language: 'it', // from the server's hello: "server", "browser" or "off"
  spokenTurn: false,
  wakeWord: 'jarvis',
};

const touchOnly = window.matchMedia('(pointer: coarse)').matches;

const STATUS_TEXT = {
  idle: touchOnly
    ? 'Tieni premuto il microfono per parlare'
    : 'Tieni premuto il microfono o la barra spaziatrice per parlare',
  listening: 'Ti ascolto…',
  thinking: 'Sto pensando…',
  speaking: 'Sto rispondendo. Premi il microfono per interrompermi',
};

let orb;

function setState(state) {
  ui.state = state;
  orb?.setState(state);
  let text = STATUS_TEXT[state];
  if (state === 'idle' && handsFree.timer) {
    text = wakeRequired() ? `Mani libere: di' «${ui.wakeWord}» e poi la tua richiesta` : 'Mani libere: parla quando vuoi';
  }
  el.status.textContent = ui.connected ? text : 'Connessione persa. Riprovo…';
}

/** A hands-free phrase not addressed to Jarvis: shown faintly, so it is clear why he did not answer. */
function ignored(text) {
  addItem('turn-note ignored', `Non era per me: «${text}»`);
}

// ---- team: specialists working in the background --------------------------------------------

const AGENTS = {
  researcher: { label: 'Ricercatore', color: '#86c5c4' },
  writer: { label: 'Scrittore', color: '#b9a7dc' },
  coder: { label: 'Programmatore', color: '#a9c58b' },
};
const SPARE_COLORS = ['#d6a7a0', '#cdbb8a', '#8fb3d9'];
const LANDING_MS = 2500;

const team = { tasks: new Map(), landing: new Set(), openDetails: new Set() };
const wideLayout = window.matchMedia('(min-width: 1200px)');

function agentInfo(id) {
  if (AGENTS[id]) return AGENTS[id];
  const hash = [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  return { label: id, color: SPARE_COLORS[hash % SPARE_COLORS.length] };
}

const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const active = (task) => ['running', 'queued', 'waiting_approval'].includes(task.status);

function clock(ms) {
  const seconds = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function stateText(task) {
  switch (task.status) {
    case 'queued': return 'in coda';
    case 'running': return `al lavoro da ${clock(Date.now() - task.startedAt)}`;
    case 'waiting_approval': return 'aspetta la tua approvazione';
    case 'completed': return `fatto in ${clock(task.finishedAt - task.startedAt)}`;
    case 'failed': return 'non riuscito';
    case 'cancelled': return 'fermato';
    case 'interrupted': return 'interrotto dal riavvio';
    default: return task.status;
  }
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text; // agent output is untrusted: never innerHTML
  return node;
}

function renderTask(task) {
  const agent = agentInfo(task.agent);
  const item = element('li', `task ${task.status}`);
  item.style.setProperty('--agent', agent.color);
  item.dataset.id = task.id;
  item.append(element('p', 'task-title', task.title));
  const meta = element('p', 'task-meta');
  meta.append(element('span', 'task-agent', agent.label), ', ', element('span', 'task-state', stateText(task)));
  item.append(meta);
  if (task.status === 'running' && task.progress) item.append(element('p', 'task-progress', task.progress));

  if (active(task)) {
    const stop = element('button', 'task-stop', 'Ferma');
    stop.type = 'button';
    stop.setAttribute('aria-label', `Ferma il lavoro: ${task.title}`);
    stop.addEventListener('click', () => send({ type: 'cancel-task', id: task.id }));
    item.append(stop);
  }

  const details = element('details');
  details.open = team.openDetails.has(task.id);
  details.addEventListener('toggle', () => {
    if (details.open) team.openDetails.add(task.id);
    else team.openDetails.delete(task.id);
  });
  details.append(element('summary', null, task.result ? 'Risultato e istruzioni' : 'Istruzioni'));
  const body = element('div', 'task-body');
  if (task.result) body.append(element('h3', null, 'Risultato'), richText(element('p'), task.result));
  if (task.error) body.append(element('h3', null, 'Motivo'), element('p', null, task.error));
  body.append(element('h3', null, 'Istruzioni'), element('p', null, task.instructions));
  if (task.workspace) body.append(element('h3', null, 'Repository'), element('p', null, task.workspace));
  if (task.costUsd != null) {
    body.append(element('h3', null, 'Costo'), element('p', null, `${task.costUsd.toLocaleString('it-IT', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} $`));
  }
  if (task.inputTokens != null) {
    const tokens = `${task.inputTokens.toLocaleString('it-IT')} in ingresso, ${(task.outputTokens ?? 0).toLocaleString('it-IT')} in uscita`;
    body.append(element('h3', null, 'Token'), element('p', null, tokens));
  }
  details.append(body);
  item.append(details);
  return item;
}

/** Working agents first (oldest first), then finished work (most recent first). */
function sortedTasks() {
  const all = [...team.tasks.values()];
  const rank = { waiting_approval: 0, running: 1, queued: 2 };
  const working = all.filter(active).sort((a, b) => rank[a.status] - rank[b.status] || a.createdAt - b.createdAt);
  const done = all.filter((t) => !active(t)).sort((a, b) => (b.finishedAt ?? 0) - (a.finishedAt ?? 0));
  return [...working, ...done];
}

function renderTeam() {
  el.tasks.replaceChildren(...sortedTasks().map(renderTask));

  const working = [...team.tasks.values()].filter(active);
  const count = (status) => working.filter((t) => t.status === status).length;
  const parts = [];
  if (count('waiting_approval')) parts.push(count('waiting_approval') === 1 ? '1 aspetta la tua approvazione' : `${count('waiting_approval')} aspettano la tua approvazione`);
  if (count('running')) parts.push(count('running') === 1 ? '1 agente al lavoro' : `${count('running')} agenti al lavoro`);
  if (count('queued')) parts.push(`${count('queued')} in coda`);
  const summary = parts.join(', ');
  el.teamSummary.textContent = summary ? summary.charAt(0).toUpperCase() + summary.slice(1) : 'Squadra a riposo';
  el.teamToggle.dataset.busy = String(working.length > 0);
  el.teamDots.replaceChildren(...working.map((task) => {
    const dot = element('i', task.status === 'running' ? null : task.status);
    dot.style.setProperty('--agent', agentInfo(task.agent).color);
    return dot;
  }));

  orb?.setAgents([...team.tasks.values()]
    .filter((task) => active(task) || team.landing.has(task.id))
    .map((task) => ({ id: task.id, color: rgb(agentInfo(task.agent).color), status: task.status })));
}

function announce(task) {
  const who = agentInfo(task.agent).label;
  const text = {
    running: `${who} al lavoro: ${task.title}`,
    completed: `${who} ha finito: ${task.title}`,
    failed: `${who} non è riuscito: ${task.title}`,
  }[task.status];
  if (text) el.announcer.textContent = text;
}

function updateTask(task) {
  const previous = team.tasks.get(task.id);
  team.tasks.set(task.id, task);
  if (previous?.status !== task.status) {
    announce(task);
    if (task.status === 'completed' || task.status === 'failed') {
      // The satellite flashes and fades out instead of vanishing
      team.landing.add(task.id);
      setTimeout(() => { team.landing.delete(task.id); renderTeam(); }, LANDING_MS);
    }
  }
  renderTeam();
}

function setTeamOpen(open) {
  document.body.classList.toggle('team-open', open);
  el.teamToggle.setAttribute('aria-expanded', String(open || wideLayout.matches));
  if (open) el.team.focus({ preventScroll: true });
}

el.team.tabIndex = -1;
el.teamToggle.addEventListener('click', () => {
  if (wideLayout.matches) {
    el.team.focus();
    el.team.scrollIntoView({ block: 'nearest' });
    return;
  }
  setTeamOpen(!document.body.classList.contains('team-open'));
});
el.teamClose.addEventListener('click', () => { setTeamOpen(false); el.teamToggle.focus(); });
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && document.body.classList.contains('team-open')) {
    setTeamOpen(false);
    el.teamToggle.focus();
  }
});
wideLayout.addEventListener('change', () => setTeamOpen(false));
setTeamOpen(false);

// Elapsed time of running tasks
setInterval(() => {
  for (const node of el.tasks.querySelectorAll('.task.running')) {
    const task = team.tasks.get(node.dataset.id);
    if (task) node.querySelector('.task-state').textContent = stateText(task);
  }
}, 1000);

// ---- WebSocket --------------------------------------------------------------------------

function conversationId() {
  const fresh = () => crypto.randomUUID();
  try {
    let id = localStorage.getItem('jarvis.conversation');
    if (!id) {
      id = fresh();
      localStorage.setItem('jarvis.conversation', id);
    }
    return id;
  } catch {
    return fresh();
  }
}

let socket;
let retryDelay = 500;

function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws?conversation=${conversationId()}`);

  socket.onopen = () => {
    retryDelay = 500;
    ui.connected = true;
    setState('idle');
  };

  socket.onclose = () => {
    const interrupted = ui.connected && (ui.state === 'thinking' || ui.state === 'speaking');
    ui.connected = false;
    setControlsEnabled(false);
    stopPlayback();
    audio.turnDone = true;
    finishStreaming();
    dismissApproval(); // the server already denied it when the connection dropped
    if (interrupted) note('Connessione persa: la risposta in corso è stata interrotta.', true);
    setState('idle');
    setTimeout(connect, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 10000);
  };

  socket.onmessage = (event) => handle(JSON.parse(event.data));
}

function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
}

function handle(msg) {
  if (settingsPanel.handle(msg)) return;
  switch (msg.type) {
    case 'hello':
      ui.wakeWord = msg.wakeWord || 'jarvis';
      el.wake.nextElementSibling.textContent = `Solo se dico «${ui.wakeWord.charAt(0).toUpperCase() + ui.wakeWord.slice(1)}»`;
      if (el.handsfree.checked) sendVoiceMode(); // a new connection starts without it
      if (settingsPanel.onHello(msg)) note('Impostazioni applicate.');
      ui.language = msg.language || 'it';
      ui.stt = msg.stt === 'browser' && !Recognition ? 'off' : msg.stt;
      ui.tts = msg.tts === 'browser' && !('speechSynthesis' in window) ? 'off' : msg.tts;
      fillProviders(msg.providers, msg.defaultProvider);
      el.speak.checked = ui.tts !== 'off';
      el.speak.disabled = ui.tts === 'off';
      setControlsEnabled(true);
      el.mic.title = ui.stt !== 'off' ? ''
        : msg.stt === 'browser' ? 'Questo browser non supporta il riconoscimento vocale'
          : 'Voce non attiva: controlla jarvis.voice.stt';
      break;
    case 'history':
      if (el.log.children.length === 0) {
        for (const m of msg.messages) {
          const item = addItem(m.role === 'user' ? 'turn-user' : 'turn-jarvis', m.text);
          if (m.role !== 'user') richText(item, m.text);
        }
      }
      break;
    case 'tasks':
      team.tasks = new Map(msg.tasks.map((task) => [task.id, task]));
      renderTeam();
      break;
    case 'task-update':
      updateTask(msg.task);
      break;
    case 'state':
      // Jarvis may start a turn by itself to report a finished task
      if (msg.state === 'thinking') audio.turnDone = false;
      if (msg.state === 'thinking' || ui.state !== 'speaking') setState(msg.state);
      break;
    case 'transcript-ignored':
      ignored(msg.text);
      break;
    case 'transcript':
      if (msg.text) addItem('turn-user', msg.text);
      else note('Non ho sentito nulla. Riprova parlando più vicino al microfono.');
      break;
    case 'token':
      appendToken(msg.text);
      if (ui.spokenTurn && ui.tts === 'browser') speakStreamed(msg.text);
      break;
    case 'audio':
      enqueueClip(msg.seq, msg.data);
      break;
    case 'approval-request':
      askApproval(msg);
      break;
    case 'approval-closed':
      closeApproval(msg.id);
      break;
    case 'done':
      finishStreaming();
      if (ui.spokenTurn && ui.tts === 'browser') speakStreamed('', true);
      audio.turnDone = true;
      if (!audio.current && audio.queue.size === 0 && browserSpeech.queued === 0) setState('idle');
      break;
    case 'error':
      finishStreaming();
      note(msg.message, true);
      break;
    default:
      console.warn('Unknown message', msg);
  }
}

function fillProviders(providers, selected) {
  el.provider.replaceChildren(...providers.map((id) => {
    const option = document.createElement('option');
    option.value = id;
    option.textContent = PROVIDER_LABELS[id] ?? id;
    option.selected = id === selected;
    return option;
  }));
}

function setControlsEnabled(enabled) {
  el.input.disabled = !enabled;
  el.send.disabled = !enabled;
  el.provider.disabled = !enabled;
  el.mic.disabled = !enabled || ui.stt === 'off';
}

// Approvals: Jarvis and several agents may ask at the same time, and requests of agents come back after a
// reconnection, so they wait in a queue and are shown one at a time.
const approvals = [];
let shownApproval = null;

function askApproval(request) {
  if (approvals.some((r) => r.id === request.id)) return;
  approvals.push(request);
  showNextApproval();
}

function showNextApproval() {
  if (shownApproval || approvals.length === 0) return;
  const request = approvals[0];
  shownApproval = request.id;
  const who = request.agent ? agentInfo(request.agent).label : 'Jarvis';
  el.approvalTitle.textContent = `${who} chiede il permesso`;
  el.approvalTask.hidden = !request.taskTitle;
  el.approvalTask.textContent = request.taskTitle ?? '';
  richText(el.approvalText, request.description);
  el.approvalText.scrollTop = 0;
  el.approval.returnValue = '';
  el.approval.onclose = () => {
    send({ type: 'approval', id: request.id, approved: el.approval.returnValue === 'allow' });
    approvals.shift();
    shownApproval = null;
    showNextApproval();
  };
  el.approval.showModal();
  // Opening moves focus inside the dialog, which can scroll a long plan: start reading from the top
  requestAnimationFrame(() => { el.approvalText.scrollTop = 0; });
}

/** Answered in another tab, expired or withdrawn by the server. */
function closeApproval(id) {
  const index = approvals.findIndex((r) => r.id === id);
  if (index < 0) return;
  approvals.splice(index, 1);
  if (shownApproval === id) {
    el.approval.onclose = null;
    el.approval.close();
    shownApproval = null;
    showNextApproval();
  }
}

/** The connection dropped: the server denied Jarvis' own requests and will show the agents' ones again. */
function dismissApproval() {
  approvals.length = 0;
  shownApproval = null;
  if (!el.approval.open) return;
  el.approval.onclose = null;
  el.approval.close();
}

// ---- input ------------------------------------------------------------------------------

el.composer.addEventListener('submit', (event) => {
  event.preventDefault();
  const text = el.input.value.trim();
  if (!text) return;
  beginTurn();
  if (el.speak.checked) audioContext(); // unlock audio playback with this user gesture
  addItem('turn-user', text);
  sendChat(text, el.speak.checked);
  el.input.value = '';
});

el.provider.addEventListener('change', () => send({ type: 'set-provider', provider: el.provider.value }));

el.mic.addEventListener('pointerdown', (event) => {
  event.preventDefault();
  el.mic.setPointerCapture(event.pointerId);
  startRecording();
});
el.mic.addEventListener('pointerup', stopRecording);
el.mic.addEventListener('pointercancel', stopRecording);

const typingTarget = (target) => target instanceof HTMLInputElement || target instanceof HTMLSelectElement;

document.addEventListener('keydown', (event) => {
  if (event.code === 'Space' && !event.repeat && !typingTarget(event.target) && !el.approval.open) {
    event.preventDefault();
    startRecording();
  }
});
document.addEventListener('keyup', (event) => {
  if (event.code === 'Space' && !typingTarget(event.target)) {
    stopRecording();
  }
});

const settingsPanel = createSettings({ send });
document.getElementById('settings-open').addEventListener('click', () => settingsPanel.open(false));

orb = new Orb(document.getElementById('orb'), currentLevel);
connect();
