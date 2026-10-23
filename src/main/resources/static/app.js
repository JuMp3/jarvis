import { Orb } from './orb.js';
import { createSettings } from './settings.js';
import { VoiceDetector } from './vad.js';
import { SpeechCapture } from './capture.js';
import { parseMarkdown, weatherIcon, SpokenText, forSpeech } from './markdown.js';
import { t, language, setLanguage, onLanguageChange, translatePage } from './i18n.js';

// Message types must match dev.jarvis.ws.Protocol and docs/ARCHITECTURE.md.

const providerLabel = (id) => ({ anthropic: 'Claude', openai: 'OpenAI', ollama: t('provider.ollama') })[id] ?? id;
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
  tasksClear: document.getElementById('tasks-clear'),
  historyClear: document.getElementById('history-clear'),
  announcer: document.getElementById('team-announcer'),
};

// ---- audio ------------------------------------------------------------------------------

const audio = {
  ctx: null,
  micAnalyser: null,
  outAnalyser: null,
  stream: null,
  capture: null,
  processor: null,
  startedAt: 0,
  queue: new Map(),
  nextSeq: 0,
  current: null,
  turnDone: true,
  recognition: null,
};

// Web Speech API, used when the server sends stt/tts = "browser". The desktop window (WebView2) has the recognition
// interface but no access to the online service behind it: it always fails with "network". Its speech synthesis
// uses the voices installed in the system, and works.
const desktopWindow = Boolean(window.chrome?.webview);
const Recognition = desktopWindow ? null : window.SpeechRecognition || window.webkitSpeechRecognition;
const noBrowserRecognition = () => t(desktopWindow ? 'voice.noRecognitionDesktop' : 'voice.noRecognition');
const browserSpeech = { pending: '', queued: 0, pulse: 0, voice: null, filter: new SpokenText() };

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
    note(t('voice.playbackFailed'), true);
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
  browserSpeech.filter = new SpokenText();
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
  const voices = speechSynthesis.getVoices().filter((v) => v.lang.toLowerCase().startsWith(language()));
  return voices.find((v) => /natural|online/i.test(v.name)) ?? voices[0] ?? null;
}

function speakInBrowser(text) {
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = language();
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
  // Cards, tables and code are shown, not read (SpokenText, as on the server)
  browserSpeech.pending += browserSpeech.filter.append(text) + (final ? browserSpeech.filter.flush() : '');
  const complete = final ? browserSpeech.pending : browserSpeech.pending.match(/^[\s\S]*([.!?…]\s|\n)/)?.[0];
  if (!complete) return;
  browserSpeech.pending = browserSpeech.pending.slice(complete.length);
  const sentence = forSpeech(complete);
  if (sentence) speakInBrowser(sentence);
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
    // Every recording goes through raw samples, sent as a 16 kHz WAV: the local whisper.cpp reads only WAV, and the
    // last moments before the start are kept (capture.js). ScriptProcessorNode is deprecated but everywhere;
    // AudioWorklet would need a module of its own.
    audio.capture = new SpeechCapture(ctx.sampleRate);
    audio.processor = ctx.createScriptProcessor(2048, 1, 1);
    audio.processor.onaudioprocess = (event) => audio.capture.push(event.inputBuffer.getChannelData(0).slice());
    audio.micSource.connect(audio.processor);
    audio.processor.connect(ctx.destination); // silent output: a processor only runs when connected
  }
}

// ---- hands-free: the voice detector starts and stops the recording ------------------------------

const handsFree = { detector: null, timer: null };
const ECHO_SENSITIVITY = 2.5; // while Jarvis speaks, only a clearly louder voice interrupts him

async function startHandsFree() {
  try {
    await openMicrophone();
  } catch {
    el.handsfree.checked = false;
    note(t('voice.noMicrophone'), true);
    return;
  }
  handsFree.detector = new VoiceDetector();
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
    audio.capture.start();
    startPieces();
    setState('listening');
  } catch (e) {
    ui.recording = false;
    el.mic.classList.remove('recording');
    note(t('voice.noMicrophone'), true);
    setState('idle');
  }
}

function startRecognition() {
  const recognition = new Recognition();
  recognition.lang = language();
  recognition.continuous = true;
  let transcript = '';
  recognition.onresult = (event) => {
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (event.results[i].isFinal) transcript += event.results[i][0].transcript;
    }
  };
  recognition.onerror = (event) => {
    if (event.error === 'network') {
      note(t('voice.recognitionNetwork'), true);
    } else if (event.error !== 'no-speech' && event.error !== 'aborted') {
      note(t('voice.recognitionFailed', { error: event.error }), true);
    }
  };
  recognition.onend = () => {
    audio.recognition = null;
    let text = transcript.trim();
    if (!text) {
      if (!el.handsfree.checked) note(t('voice.nothingHeard'));
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
  if (audio.capture?.recording) finishPieces();
}

// ---- a phrase in pieces: at each short pause what was said so far goes to the server, which transcribes it while
// the user goes on; at the end only the last piece is left (Utterance on the server)

const PIECE_PAUSE_MS = 300;
const MIN_PIECE_MS = 1500; // shorter pieces give Whisper too little to understand
const pieces = { timer: null, detector: null, sent: 0, speech: false };

function startPieces() {
  pieces.sent = 0;
  pieces.speech = false;
  pieces.detector = new VoiceDetector({ startMs: 100, endMs: PIECE_PAUSE_MS });
  pieces.timer = setInterval(() => {
    const event = pieces.detector.update(levelOf(audio.micAnalyser), performance.now());
    if (event === 'start') pieces.speech = true;
    if (event === 'end' && audio.capture.pendingMs() >= MIN_PIECE_MS) {
      sendPiece(audio.capture.cut());
      pieces.speech = false;
    }
  }, 30);
}

function sendPiece(buffer) {
  if (pieces.sent === 0) send({ type: 'speech-start' });
  socket.send(buffer);
  pieces.sent += 1;
}

/** The last piece and the end of the phrase; a phrase never cut goes as one recording, as before. */
function finishPieces() {
  clearInterval(pieces.timer);
  pieces.timer = null;
  const rest = audio.capture.stop();
  if (pieces.sent === 0) {
    sendAudio(rest);
    return;
  }
  // Cut at a pause, the rest may be silence only: Whisper would make up words from it
  if (pieces.speech) socket.send(rest);
  send({ type: 'speech-end' });
  ui.spokenTurn = ui.tts !== 'off';
  setState('thinking');
}

function sendAudio(buffer) {
  const duration = performance.now() - audio.startedAt;
  if (duration < MIN_RECORDING_MS || buffer.byteLength === 0) {
    note(t('voice.tooShort'));
    setState('idle');
    return;
  }
  ui.spokenTurn = ui.tts !== 'off';
  setState('thinking');
  socket.send(buffer);
}

// ---- conversation -----------------------------------------------------------------------

let streamingItem = null;
let streamingText = '';
let partialItem = null; // the phrase being transcribed while the user speaks
let renderPending = false;

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
    streamingText = '';
  }
  streamingText += text;
  if (renderPending) return;
  renderPending = true; // at most one Markdown render per frame
  requestAnimationFrame(() => {
    renderPending = false;
    if (!streamingItem) return;
    renderMarkdown(streamingItem, streamingText);
    el.conversation.scrollTop = el.conversation.scrollHeight;
  });
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

// ---- Markdown and cards: built through the DOM from markdown.js, never innerHTML ---------------

function renderMarkdown(node, text) {
  node.replaceChildren(...parseMarkdown(text).map(markdownBlock));
  return node;
}

function inlineNodes(parent, nodes) {
  for (const node of nodes) {
    if (node.type === 'text') parent.append(node.text);
    else if (node.type === 'code') parent.append(element('code', null, node.text));
    else if (node.type === 'link') {
      const link = element('a', null, node.text);
      link.href = node.href; // http(s) only, checked by the parser
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      parent.append(link);
    } else parent.append(inlineNodes(element(node.type === 'strong' ? 'strong' : 'em'), node.children));
  }
  return parent;
}

function markdownBlock(block) {
  switch (block.type) {
    case 'heading': return inlineNodes(element(`h${Math.min(block.level + 2, 6)}`, 'md-heading'), block.inline);
    case 'paragraph': return inlineNodes(element('p'), block.inline);
    case 'quote': return inlineNodes(element('blockquote'), block.inline);
    case 'rule': return element('hr');
    case 'code': {
      const pre = element('pre');
      pre.append(element('code', null, block.text));
      return pre;
    }
    case 'list': {
      const list = element(block.ordered ? 'ol' : 'ul');
      if (block.ordered && block.start > 1) list.start = block.start;
      for (const item of block.items) {
        const li = inlineNodes(element('li'), item.inline);
        if (item.list) li.append(markdownBlock(item.list));
        list.append(li);
      }
      return list;
    }
    case 'table': {
      const table = element('table');
      const head = element('tr');
      block.header.forEach((cell) => head.append(inlineNodes(element('th'), cell)));
      table.append(element('thead'), element('tbody'));
      table.tHead.append(head);
      for (const row of block.rows) {
        const tr = element('tr');
        block.header.forEach((_, i) => tr.append(inlineNodes(element('td'), row[i] ?? [])));
        table.tBodies[0].append(tr);
      }
      const scroll = element('div', 'md-table');
      scroll.append(table);
      return scroll;
    }
    case 'pending-card': return element('div', 'card pending', t('card.pending'));
    case 'card': return card(block.card);
    default: return element('p', null, '');
  }
}

/** Cards the model can add to a written answer (AgentService.WRITTEN); an unknown one is shown as data. */
function card(data) {
  if (data.type === 'weather') return weatherCard(data);
  if (data.type === 'agenda') return agendaCard(data);
  if (data.type === 'emails') return emailsCard(data);
  if (data.type === 'facts') return factsCard(data);
  if (data.type === 'sources') return sourcesCard(data);
  const pre = element('pre');
  pre.append(element('code', null, JSON.stringify(data, null, 2)));
  return pre;
}

const degrees = (value) => (Number.isFinite(Number(value)) && value !== '' && value != null ? `${Math.round(Number(value))}°` : '');

function weatherCard(data) {
  const box = element('figure', 'card weather');
  const now = data.now ?? {};
  const head = element('div', 'weather-now');
  head.append(element('span', 'weather-icon', weatherIcon(now.condition)));
  const where = element('div', 'weather-where');
  where.append(element('span', 'weather-place', data.place ?? t('weather.title')));
  if (now.condition) where.append(element('span', 'weather-condition', now.condition));
  head.append(where, element('span', 'weather-temp', degrees(now.temp)));
  box.append(head);

  const details = [
    now.feels != null && t('weather.feels', { value: degrees(now.feels) }),
    now.humidity != null && t('weather.humidity', { value: Math.round(now.humidity) }),
    now.wind != null && t('weather.wind', { value: Math.round(now.wind) }),
  ].filter(Boolean);
  if (details.length) box.append(element('p', 'weather-details', details.join(' · ')));

  if (Array.isArray(data.days) && data.days.length) {
    const days = element('div', 'weather-days'); // not ol/li: the list styles of the answer do not apply
    days.setAttribute('role', 'list');
    for (const day of data.days.slice(0, 7)) {
      const tile = element('div', 'weather-day');
      tile.setAttribute('role', 'listitem');
      tile.append(element('span', 'weather-day-name', day.day ?? ''),
        element('span', 'weather-icon', weatherIcon(day.condition)),
        element('span', 'weather-range', `${degrees(day.max)} ${degrees(day.min)}`.trim()));
      if (day.rain != null) tile.append(element('span', 'weather-rain', `💧 ${Math.round(day.rain)}%`));
      tile.title = day.condition ?? '';
      days.append(tile);
    }
    box.append(days);
  }
  box.setAttribute('aria-label', [data.place, now.condition, degrees(now.temp)].filter(Boolean).join(', '));
  return box;
}

/** The head of a card: its title, and on the right a short note (a date, a count). */
function cardHead(title, note) {
  const head = element('header', 'card-head');
  head.append(element('span', 'card-title', title ?? ''));
  if (note) head.append(element('span', 'card-note', note));
  return head;
}

const text = (value) => (value == null ? '' : String(value));

/** The day's events on a line of time: hour, title, place. */
function agendaCard(data) {
  const box = element('figure', 'card agenda');
  const events = Array.isArray(data.events) ? data.events : [];
  box.append(cardHead(data.title ?? t('card.agenda'), data.date));
  const list = element('ol', 'agenda-events');
  for (const event of events.slice(0, 12)) {
    const item = element('li', 'agenda-event');
    const when = element('span', 'agenda-time', text(event.time));
    if (event.end) when.append(element('span', 'agenda-end', text(event.end)));
    const what = element('span', 'agenda-what');
    what.append(element('span', 'agenda-title', text(event.title)));
    if (event.place) what.append(element('span', 'agenda-place', text(event.place)));
    item.append(when, what);
    list.append(item);
  }
  if (!events.length) list.append(element('li', 'card-empty', t('card.agendaEmpty')));
  box.append(list);
  return box;
}

/** Mail as it would be on a desk: who, about what, when; unread ones marked. */
function emailsCard(data) {
  const box = element('figure', 'card emails');
  const items = Array.isArray(data.items) ? data.items : [];
  box.append(cardHead(data.title ?? t('card.emails'), items.length ? String(items.length) : null));
  const list = element('ul', 'email-list');
  for (const mail of items.slice(0, 10)) {
    const row = element('li', mail.unread ? 'email unread' : 'email');
    const from = text(mail.from);
    const initial = element('span', 'email-initial', from.trim().charAt(0).toUpperCase() || '?');
    initial.style.setProperty('--hue', String([...from].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 40)));
    initial.setAttribute('aria-hidden', 'true');
    const body = element('span', 'email-body');
    body.append(element('span', 'email-from', from), element('span', 'email-subject', text(mail.subject)));
    row.append(initial, body, element('span', 'email-time', text(mail.time)));
    if (mail.unread) row.setAttribute('aria-label', `${t('card.unread')}: ${from}, ${text(mail.subject)}`);
    list.append(row);
  }
  box.append(list);
  return box;
}

/** A few numbers worth seeing at a glance. */
function factsCard(data) {
  const box = element('figure', 'card facts');
  if (data.title) box.append(cardHead(data.title));
  const grid = element('dl', 'facts-grid');
  for (const fact of (Array.isArray(data.items) ? data.items : []).slice(0, 8)) {
    const tile = element('div', 'fact');
    tile.append(element('dt', 'fact-label', text(fact.label)), element('dd', 'fact-value', text(fact.value)));
    if (fact.note) tile.append(element('dd', 'fact-note', text(fact.note)));
    grid.append(tile);
  }
  box.append(grid);
  return box;
}

/** Where an answer comes from: links, http(s) only, with their site. */
function sourcesCard(data) {
  const box = element('figure', 'card sources');
  box.append(cardHead(t('card.sources')));
  const list = element('ul', 'source-list');
  for (const source of (Array.isArray(data.items) ? data.items : []).slice(0, 8)) {
    let url;
    try {
      url = new URL(text(source.url));
    } catch {
      continue;
    }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') continue;
    const link = element('a', 'source');
    link.href = url.href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.append(element('span', 'source-title', text(source.title) || url.hostname),
      element('span', 'source-site', url.hostname.replace(/^www\./, '')));
    const item = element('li');
    item.append(link);
    list.append(item);
  }
  box.append(list);
  return box;
}

/** Under each answer: copy it. The text is the model's, as written. */
function answerActions(item, markdown) {
  const actions = element('div', 'turn-actions');
  const copy = element('button', 'turn-action', t('turn.copy'));
  copy.type = 'button';
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(markdown);
      copy.textContent = t('turn.copied');
      setTimeout(() => { copy.textContent = t('turn.copy'); }, 1500);
    } catch { /* the browser refused: nothing to undo */ }
  });
  actions.append(copy);
  item.append(actions);
}

function finishStreaming() {
  if (streamingItem) {
    renderMarkdown(streamingItem, streamingText);
    answerActions(streamingItem, streamingText);
    streamingItem.classList.remove('streaming');
    stageLatest();
    streamingItem = null;
  }
}

// ---- state ------------------------------------------------------------------------------

const ui = {
  state: 'idle', recording: false, connected: false,
  stt: 'off', tts: 'off', // from the server's hello: "server", "browser" or "off"
  sttRequested: 'off', // what the settings ask, even when this page cannot do it
  spokenTurn: false,
  wakeWord: 'jarvis',
};

const touchOnly = window.matchMedia('(pointer: coarse)').matches;

const statusText = (state) => t(state === 'idle' && touchOnly ? 'status.idleTouch' : `status.${state}`);

let orb;

function setState(state) {
  ui.state = state;
  document.body.dataset.state = ui.connected ? state : 'offline'; // the lamp's light follows it (style.css)
  orb?.setState(state);
  let text = statusText(state);
  if (state === 'idle' && handsFree.timer) {
    text = wakeRequired() ? t('status.handsfreeWake', { word: ui.wakeWord }) : t('status.handsfree');
  }
  el.status.textContent = ui.connected ? text : t('status.disconnected');
}

/** A hands-free phrase not addressed to Jarvis: shown faintly, so it is clear why he did not answer. */
function ignored(text) {
  addItem('turn-note ignored', t('turn.ignored', { text }));
}

// ---- team: specialists working in the background --------------------------------------------

const AGENT_COLORS = { researcher: '#86c5c4', writer: '#b9a7dc', coder: '#a9c58b' };
const SPARE_COLORS = ['#d6a7a0', '#cdbb8a', '#8fb3d9'];
const LANDING_MS = 2500;

const team = { tasks: new Map(), landing: new Set(), openDetails: new Set() };

function agentInfo(id) {
  const label = t(`agent.${id}`);
  const hash = [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  return {
    label: label === `agent.${id}` ? id : label, // agents added in configuration keep their id
    color: AGENT_COLORS[id] ?? SPARE_COLORS[hash % SPARE_COLORS.length],
  };
}

const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const active = (task) => ['running', 'queued', 'waiting_approval'].includes(task.status);

function clock(ms) {
  const seconds = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function stateText(task) {
  switch (task.status) {
    case 'queued': return t('task.queued');
    case 'running': return t('task.running', { time: clock(Date.now() - task.startedAt) });
    case 'waiting_approval': return t('task.waiting');
    case 'completed': return t('task.completed', { time: clock(task.finishedAt - task.startedAt) });
    case 'failed': return t('task.failed');
    case 'cancelled': return t('task.cancelled');
    case 'interrupted': return t('task.interrupted');
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
  const item = element('li', `task ${task.status}${task.parentId ? ' subtask' : ''}`);
  item.style.setProperty('--agent', agent.color);
  item.dataset.id = task.id;
  item.append(element('p', 'task-title', task.title));
  const meta = element('p', 'task-meta');
  meta.append(element('span', 'task-agent', agent.label), ', ', element('span', 'task-state', stateText(task)));
  item.append(meta);
  if (task.status === 'running' && task.progress) item.append(element('p', 'task-progress', task.progress));

  if (active(task)) {
    const stop = element('button', 'task-stop', t('task.stop'));
    stop.type = 'button';
    stop.setAttribute('aria-label', t('task.stopLabel', { title: task.title }));
    stop.addEventListener('click', () => send({ type: 'cancel-task', id: task.id }));
    item.append(stop);
  }

  const details = element('details');
  details.open = team.openDetails.has(task.id);
  details.addEventListener('toggle', () => {
    if (details.open) team.openDetails.add(task.id);
    else team.openDetails.delete(task.id);
  });
  details.append(element('summary', null, t(task.result ? 'task.resultAndInstructions' : 'task.instructions')));
  const body = element('div', 'task-body');
  if (task.result) body.append(element('h3', null, t('task.result')), renderMarkdown(element('div', 'task-result'), task.result));
  if (task.error) body.append(element('h3', null, t('task.reason')), element('p', null, task.error));
  body.append(element('h3', null, t('task.instructions')), element('p', null, task.instructions));
  if (task.workspace) body.append(element('h3', null, t('task.repository')), element('p', null, task.workspace));
  if (task.costUsd != null) {
    const cost = task.costUsd.toLocaleString(language(), { style: 'currency', currency: 'USD' });
    body.append(element('h3', null, t('task.cost')), element('p', null, cost));
  }
  if (task.inputTokens != null) {
    const tokens = t('task.tokenCounts', {
      input: task.inputTokens.toLocaleString(language()), output: (task.outputTokens ?? 0).toLocaleString(language()),
    });
    body.append(element('h3', null, t('task.tokens')), element('p', null, tokens));
  }
  details.append(body);
  item.append(details);
  return item;
}

/** Working agents first (oldest first), then finished work (most recent first); each part under its task. */
function sortedTasks() {
  const all = [...team.tasks.values()];
  const rank = { waiting_approval: 0, running: 1, queued: 2 };
  const top = all.filter((t) => !t.parentId || !team.tasks.has(t.parentId));
  const working = top.filter(active).sort((a, b) => rank[a.status] - rank[b.status] || a.createdAt - b.createdAt);
  const done = top.filter((t) => !active(t)).sort((a, b) => (b.finishedAt ?? 0) - (a.finishedAt ?? 0));
  return [...working, ...done].flatMap((task) => [task,
    ...all.filter((t) => t.parentId === task.id).sort((a, b) => a.createdAt - b.createdAt)]);
}

function renderTeam() {
  el.tasks.replaceChildren(...sortedTasks().map(renderTask));

  const working = [...team.tasks.values()].filter(active);
  const count = (status) => working.filter((t) => t.status === status).length;
  const parts = [];
  const waiting = count('waiting_approval');
  const running = count('running');
  if (waiting) parts.push(waiting === 1 ? t('team.waitingOne') : t('team.waitingMany', { count: waiting }));
  if (running) parts.push(running === 1 ? t('team.runningOne') : t('team.runningMany', { count: running }));
  if (count('queued')) parts.push(t('team.queued', { count: count('queued') }));
  const summary = parts.join(', ');
  el.teamSummary.textContent = summary ? summary.charAt(0).toUpperCase() + summary.slice(1) : t('team.idle');
  el.teamToggle.dataset.busy = String(working.length > 0);
  el.tasksClear.hidden = working.length === team.tasks.size;
  el.teamDots.replaceChildren(...working.map(avatar));

  orb?.setAgents([...team.tasks.values()]
    .filter((task) => active(task) || team.landing.has(task.id))
    .map((task) => ({ id: task.id, color: rgb(agentInfo(task.agent).color), status: task.status })));
}

function announce(task) {
  const who = agentInfo(task.agent).label;
  if (['running', 'completed', 'failed'].includes(task.status)) {
    el.announcer.textContent = t(`announce.${task.status}`, { who, title: task.title });
  }
}

function updateTask(task) {
  const previous = team.tasks.get(task.id);
  team.tasks.set(task.id, task);
  if (previous?.status !== task.status) {
    announce(task);
    if (task.agent === 'routine') routineNotification(task);
    if (task.status === 'completed' || task.status === 'failed') {
      // The satellite flashes and fades out instead of vanishing
      team.landing.add(task.id);
      setTimeout(() => { team.landing.delete(task.id); renderTeam(); }, LANDING_MS);
    }
  }
  renderTeam();
}

/**
 * A routine ran while Jarvis was not in front of the user (window in the tray, another tab): a system notification.
 * The desktop app has its own (Tauri), the browser asks for permission the first time a routine starts.
 */
function routineNotification(task) {
  if (task.status === 'queued' && 'Notification' in window && Notification.permission === 'default'
    && !window.__TAURI__) {
    Notification.requestPermission();
  }
  if (!['completed', 'failed'].includes(task.status) || (!document.hidden && document.hasFocus())) return;
  const title = t('routine.notification', { title: task.title });
  const body = (task.result ?? task.error ?? '').replace(/[#*_`>|]/g, '').replace(/\s+/g, ' ').trim().slice(0, 180);
  const desktop = window.__TAURI__?.notification;
  if (desktop) desktop.sendNotification({ title, body });
  else if ('Notification' in window && Notification.permission === 'granted') new Notification(title, { body });
}

/**
 * An agent at work in the dock: its initial in its colour. The ring fills with its phases when the specialist knows
 * them (step of steps: the coder, a reviewed work); otherwise it only turns, which says it works and nothing more.
 */
function avatar(task) {
  const agent = agentInfo(task.agent);
  const known = task.steps > 0 && task.step > 0;
  const state = task.status === 'waiting_approval' ? 'waiting' : task.status === 'queued' ? 'queued' : known ? '' : 'spin';
  const node = element('span', `ava ${state}`.trim(), agent.label.charAt(0).toUpperCase());
  node.style.setProperty('--agent', agent.color);
  // a phase counts as done once the next one starts
  if (known) node.style.setProperty('--done', String(Math.max(0.08, (task.step - 1) / task.steps)));
  return node;
}

function setTeamOpen(open) {
  document.body.classList.toggle('team-open', open);
  el.teamToggle.setAttribute('aria-expanded', String(open));
  if (open) el.team.focus({ preventScroll: true });
}

el.team.tabIndex = -1;
el.teamToggle.addEventListener('click', () => setTeamOpen(!document.body.classList.contains('team-open')));
el.teamClose.addEventListener('click', () => { setTeamOpen(false); el.teamToggle.focus(); });

// Both only take things off the screen (the database keeps them for long-term memory), and ask first.
// Work in progress is never removed.
el.tasksClear.addEventListener('click', () => {
  if (confirm(t('team.clearConfirm'))) send({ type: 'clear-tasks' });
});
el.historyClear.addEventListener('click', () => {
  if (confirm(t('history.clearConfirm'))) {
    send({ type: 'clear-history' });
  }
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && document.body.classList.contains('team-open')) {
    setTeamOpen(false);
    el.teamToggle.focus();
  }
});
setTeamOpen(false);

// Elapsed time of running tasks
setInterval(() => {
  for (const node of el.tasks.querySelectorAll('.task.running')) {
    const task = team.tasks.get(node.dataset.id);
    if (task) node.querySelector('.task-state').textContent = stateText(task);
  }
}, 1000);

// ---- WebSocket --------------------------------------------------------------------------

/** The conversation this page keeps; none yet: the server resumes the latest one and says which in hello. */
function conversationId() {
  try {
    return localStorage.getItem('jarvis.conversation');
  } catch {
    return null;
  }
}

function keepConversation(id) {
  try {
    if (id) localStorage.setItem('jarvis.conversation', id);
  } catch { /* storage disabled: the server resumes the latest conversation at each connection */ }
}

let socket;
let retryDelay = 500;

function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const conversation = conversationId();
  socket = new WebSocket(`${scheme}://${location.host}/ws${conversation ? `?conversation=${encodeURIComponent(conversation)}` : ''}`);

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
    if (interrupted) note(t('turn.connectionLost'), true);
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
      keepConversation(msg.conversation);
      applyTheme(msg.theme);
      setLanguage(msg.language); // first: everything below speaks it
      ui.wakeWord = msg.wakeWord || 'jarvis';
      wakeLabel();
      if (el.handsfree.checked) sendVoiceMode(); // a new connection starts without it
      if (settingsPanel.onHello(msg)) note(t('turn.settingsApplied'));
      ui.sttRequested = msg.stt;
      ui.stt = msg.stt === 'browser' && !Recognition ? 'off' : msg.stt;
      if (msg.stt === 'browser' && !Recognition && !ui.toldNoRecognition) {
        ui.toldNoRecognition = true; // once per page, not at every reconnection
        note(noBrowserRecognition(), true);
      }
      ui.tts = msg.tts === 'browser' && !('speechSynthesis' in window) ? 'off' : msg.tts;
      fillProviders(msg.providers, msg.defaultProvider);
      el.speak.checked = ui.tts !== 'off';
      el.speak.disabled = ui.tts === 'off';
      setControlsEnabled(true);
      micTitle();
      break;
    case 'history':
      if (el.log.children.length === 0) {
        for (const m of msg.messages) {
          const item = addItem(m.role === 'user' ? 'turn-user' : 'turn-jarvis', m.text);
          if (m.role !== 'user') {
            renderMarkdown(item, m.text);
            answerActions(item, m.text);
          }
        }
      }
      break;
    case 'history-cleared':
      streamingItem = null;
      el.log.replaceChildren();
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
    case 'transcript-partial':
      partialItem ??= addItem('turn-user partial', '');
      partialItem.textContent = msg.text;
      el.conversation.scrollTop = el.conversation.scrollHeight;
      break;
    case 'transcript-ignored':
      dropPartial();
      ignored(msg.text);
      break;
    case 'transcript':
      dropPartial();
      if (msg.text) addItem('turn-user', msg.text);
      else note(t('voice.nothingHeard'));
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
      dropPartial();
      note(msg.message, true);
      break;
    default:
      console.warn('Unknown message', msg);
  }
}

/** The final transcript replaces the partial one (it may differ: the wake word is taken out). */
function dropPartial() {
  partialItem?.remove();
  partialItem = null;
}

function fillProviders(providers, selected) {
  el.provider.replaceChildren(...providers.map((id) => {
    const option = document.createElement('option');
    option.value = id;
    option.textContent = providerLabel(id);
    option.selected = id === selected;
    return option;
  }));
}

function setControlsEnabled(enabled) {
  el.input.disabled = !enabled;
  el.send.disabled = !enabled;
  el.provider.disabled = !enabled;
  el.mic.disabled = !enabled || ui.stt === 'off';
  el.historyClear.disabled = !enabled;
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
  el.approvalTitle.textContent = t('approval.title', { who });
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

/** "Only when I say «Jarvis»", with the wake word of the settings. */
function wakeLabel() {
  el.wake.nextElementSibling.textContent = t('header.wake', { word: ui.wakeWord.charAt(0).toUpperCase() + ui.wakeWord.slice(1) });
}

function micTitle() {
  el.mic.title = ui.stt !== 'off' ? '' : ui.sttRequested === 'browser' ? noBrowserRecognition() : t('voice.off');
}

/** night, day or auto (as the system): saved for the next page, which shows it before the server answers. */
function applyTheme(theme) {
  const chosen = ['night', 'day'].includes(theme) ? theme : 'auto';
  document.documentElement.dataset.theme = chosen;
  try {
    localStorage.setItem('jarvis.theme', chosen);
  } catch { /* storage disabled */ }
}

try {
  document.documentElement.dataset.theme = localStorage.getItem('jarvis.theme') ?? 'auto';
} catch { /* storage disabled */ }

/**
 * The exchange in progress is the scene: the latest answer large (said big when short), the earlier ones quieter;
 * the rail lists your questions, to go back to them.
 */
function stageLatest() {
  const answers = el.log.querySelectorAll('.turn-jarvis');
  answers.forEach((node, i) => {
    const latest = i === answers.length - 1;
    node.classList.toggle('latest', latest);
    node.classList.toggle('short', latest && node.textContent.length < 200
      && !node.querySelector('ul, ol, table, pre, h3, h4, blockquote'));
  });
}

const historyList = document.getElementById('history');

function renderHistory() {
  const questions = [...el.log.querySelectorAll('.turn-user:not(.partial)')];
  historyList.replaceChildren(...questions.map((question, i) => {
    const button = element('button', null, question.textContent);
    button.type = 'button';
    button.title = question.textContent;
    button.addEventListener('click', () => question.scrollIntoView({ block: 'start', behavior: 'smooth' }));
    const item = element('li', i === questions.length - 1 ? 'current' : null);
    item.append(button);
    return item;
  }));
}

new MutationObserver(() => {
  stageLatest();
  renderHistory();
}).observe(el.log, { childList: true });

/** The empty conversation: a greeting for the time of day, and a few things to ask. */
function welcome() {
  const hour = new Date().getHours();
  document.getElementById('greeting').textContent =
    t(hour < 5 ? 'welcome.night' : hour < 12 ? 'welcome.morning' : hour < 18 ? 'welcome.afternoon' : 'welcome.evening');
  const list = document.getElementById('suggestions');
  list.replaceChildren(...['welcome.ask1', 'welcome.ask2', 'welcome.ask3', 'welcome.ask4'].map((key) => {
    const button = element('button', 'suggestion', t(key));
    button.type = 'button';
    button.addEventListener('click', () => {
      if (el.input.disabled) return;
      el.input.value = t(key);
      el.composer.requestSubmit();
    });
    const item = element('li');
    item.append(button);
    return item;
  }));
}

// Everything written with t() is written again: the fixed texts, and what the page drew itself
onLanguageChange(() => {
  translatePage();
  welcome();
  browserSpeech.voice = null; // a voice of the new language
  wakeLabel();
  micTitle();
  for (const option of el.provider.options) option.textContent = providerLabel(option.value);
  setState(ui.state);
  renderTeam();
  if (shownApproval) {
    const request = approvals[0];
    el.approvalTitle.textContent = t('approval.title', { who: request.agent ? agentInfo(request.agent).label : 'Jarvis' });
  }
});
translatePage();
welcome();

const settingsPanel = createSettings({ send });
document.getElementById('settings-open').addEventListener('click', () => settingsPanel.open(false));

orb = new Orb(document.getElementById('orb'), currentLevel);
connect();
