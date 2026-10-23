// Microphone capture for hands-free mode. Pure logic: the caller feeds it the raw samples of the microphone.
// The voice detector fires only after the first syllable, so the last moments before the start are kept and
// put in front of the recording: without them "cosa puoi fare" reaches Whisper as "sta poi fare".

export class SpeechCapture {
  constructor(sampleRate, prerollMs = 600) {
    this.sampleRate = sampleRate;
    this.prerollSamples = Math.round(sampleRate * prerollMs / 1000);
    this.preroll = [];
    this.recording = null;
  }

  /** @param {Float32Array} samples a copy: the audio graph reuses its buffers */
  push(samples) {
    if (this.recording) {
      this.recording.push(samples);
      return;
    }
    this.preroll.push(samples);
    let kept = this.preroll.reduce((sum, chunk) => sum + chunk.length, 0);
    while (this.preroll.length > 1 && kept - this.preroll[0].length >= this.prerollSamples) {
      kept -= this.preroll.shift().length;
    }
  }

  start() {
    this.recording = this.preroll;
    this.preroll = [];
  }

  /** @returns {ArrayBuffer} what was recorded since the start or the last cut, as a WAV; the recording goes on */
  cut() {
    const chunks = this.recording ?? [];
    if (this.recording) this.recording = [];
    return encodeWav(chunks, this.sampleRate);
  }

  /** Milliseconds recorded since the start or the last cut. */
  pendingMs() {
    return (this.recording ?? []).reduce((sum, chunk) => sum + chunk.length, 0) * 1000 / this.sampleRate;
  }

  /** @returns {ArrayBuffer} the recording (since the last cut, if any) as a 16 kHz mono WAV */
  stop() {
    const chunks = this.recording ?? [];
    this.recording = null;
    return encodeWav(chunks, this.sampleRate);
  }
}

/** 16-bit PCM WAV, downsampled by averaging: plenty for speech, and what Whisper works on anyway. */
export function encodeWav(chunks, inputRate, outputRate = 16000) {
  const input = new Float32Array(chunks.reduce((sum, chunk) => sum + chunk.length, 0));
  let offset = 0;
  for (const chunk of chunks) {
    input.set(chunk, offset);
    offset += chunk.length;
  }
  const ratio = inputRate / outputRate;
  const length = Math.floor(input.length / ratio);
  const view = new DataView(new ArrayBuffer(44 + length * 2));
  const text = (at, value) => [...value].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)));
  text(0, 'RIFF');
  view.setUint32(4, 36 + length * 2, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, outputRate, true);
  view.setUint32(28, outputRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, 'data');
  view.setUint32(40, length * 2, true);
  for (let i = 0; i < length; i++) {
    const from = Math.floor(i * ratio);
    const to = Math.max(from + 1, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = from; j < to; j++) sum += input[j];
    const sample = Math.max(-1, Math.min(1, sum / (to - from)));
    view.setInt16(44 + i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }
  return view.buffer;
}
