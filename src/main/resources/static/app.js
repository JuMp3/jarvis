import { Orb } from './orb.js';

// Message types must match dev.jarvis.ws.Protocol and docs/ARCHITECTURE.md.

const PROVIDER_LABELS = { anthropic: 'Claude', openai: 'OpenAI', ollama: 'Ollama (locale)' };
const MIN_RECORDING_MS = 350;

const el = {
  provider: document.getElementById('provider'),
  speak: document.getElementById('speak'),
  status: document.getElementById('status'),
  log: document.getElementById('log'),
  conversation: document.querySelector('.conversation'),
  composer: document.getElementById('composer'),
  input: document.getElementById('input'),
  send: document.getElementById('send'),
  mic: document.getElementById('mic'),
  approval: document.getElementById('approval'),
  approvalText: document.getElementById('approval-text'),
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
};

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
  if (audio.current) {
    audio.current.onended = null;
    try { audio.current.stop(); } catch { /* already stopped */ }
    audio.current = null;
  }
}

/** Starts a new turn locally: forget pending audio, interrupt Jarvis if it is still answering. */
function beginTurn() {
  const busy = ui.state === 'thinking' || ui.state === 'speaking' || audio.current;
  stopPlayback();
  audio.turnDone = false;
  if (busy) send({ type: 'cancel' });
  finishStreaming();
}

// ---- microphone -------------------------------------------------------------------------

async function startRecording() {
  if (ui.recording || el.mic.disabled) return;
  ui.recording = true;
  el.mic.classList.add('recording');
  beginTurn();
  try {
    const ctx = audioContext();
    if (!audio.stream) {
      audio.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audio.micAnalyser = ctx.createAnalyser();
      audio.micAnalyser.fftSize = 512;
      ctx.createMediaStreamSource(audio.stream).connect(audio.micAnalyser);
    }
    if (!ui.recording) return; // released while the permission prompt was open
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']
      .find((type) => MediaRecorder.isTypeSupported(type));
    audio.recorder = new MediaRecorder(audio.stream, mimeType ? { mimeType } : undefined);
    audio.chunks = [];
    audio.recorder.ondataavailable = (event) => event.data.size && audio.chunks.push(event.data);
    audio.recorder.onstop = sendRecording;
    audio.startedAt = performance.now();
    audio.recorder.start();
    setState('listening');
  } catch (e) {
    ui.recording = false;
    el.mic.classList.remove('recording');
    note('Microfono non disponibile: consenti l\'accesso al microfono nelle impostazioni del browser.', true);
    setState('idle');
  }
}

function stopRecording() {
  if (!ui.recording) return;
  ui.recording = false;
  el.mic.classList.remove('recording');
  if (audio.recorder && audio.recorder.state === 'recording') {
    audio.recorder.stop();
  }
}

async function sendRecording() {
  const duration = performance.now() - audio.startedAt;
  const blob = new Blob(audio.chunks, { type: audio.recorder.mimeType });
  if (duration < MIN_RECORDING_MS || blob.size === 0) {
    note('Tieni premuto mentre parli, poi rilascia.');
    setState('idle');
    return;
  }
  setState('thinking');
  socket.send(await blob.arrayBuffer());
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

function finishStreaming() {
  if (streamingItem) {
    streamingItem.classList.remove('streaming');
    streamingItem = null;
  }
}

// ---- state ------------------------------------------------------------------------------

const ui = { state: 'idle', recording: false, voiceEnabled: false, connected: false };

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
  el.status.textContent = ui.connected ? STATUS_TEXT[state] : 'Connessione persa. Riprovo…';
}

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
    ui.connected = false;
    setControlsEnabled(false);
    stopPlayback();
    finishStreaming();
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
  switch (msg.type) {
    case 'hello':
      ui.voiceEnabled = msg.voiceEnabled;
      fillProviders(msg.providers, msg.defaultProvider);
      el.speak.checked = msg.voiceEnabled;
      el.speak.disabled = !msg.voiceEnabled;
      setControlsEnabled(true);
      if (!msg.voiceEnabled) {
        el.mic.title = 'Voce non configurata: imposta jarvis.voice.openai-api-key';
      }
      break;
    case 'state':
      if (msg.state === 'thinking' || ui.state !== 'speaking') setState(msg.state);
      break;
    case 'transcript':
      if (msg.text) addItem('turn-user', msg.text);
      else note('Non ho sentito nulla. Riprova parlando più vicino al microfono.');
      break;
    case 'token':
      appendToken(msg.text);
      break;
    case 'audio':
      enqueueClip(msg.seq, msg.data);
      break;
    case 'approval-request':
      askApproval(msg.id, msg.description);
      break;
    case 'done':
      finishStreaming();
      audio.turnDone = true;
      if (!audio.current && audio.queue.size === 0) setState('idle');
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
  el.mic.disabled = !enabled || !ui.voiceEnabled;
}

function askApproval(id, description) {
  el.approvalText.textContent = description;
  el.approval.returnValue = '';
  el.approval.onclose = () => {
    send({ type: 'approval', id, approved: el.approval.returnValue === 'allow' });
  };
  el.approval.showModal();
}

// ---- input ------------------------------------------------------------------------------

el.composer.addEventListener('submit', (event) => {
  event.preventDefault();
  const text = el.input.value.trim();
  if (!text) return;
  beginTurn();
  if (el.speak.checked) audioContext(); // unlock audio playback with this user gesture
  addItem('turn-user', text);
  send({ type: 'chat', text, provider: el.provider.value, speak: el.speak.checked });
  el.input.value = '';
  setState('thinking');
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

orb = new Orb(document.getElementById('orb'), currentLevel);
connect();
