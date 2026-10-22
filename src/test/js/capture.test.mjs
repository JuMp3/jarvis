// Run with: node --test src/test/js/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SpeechCapture, encodeWav } from '../../main/resources/static/capture.js';

const chunk = (value, length = 4800) => new Float32Array(length).fill(value); // 100 ms at 48 kHz

test('the recording starts with the moments before the voice was detected', () => {
  const capture = new SpeechCapture(48000, 600);
  for (let i = 0; i < 20; i++) capture.push(chunk(0)); // 2 s of silence: only about 600 ms are kept
  capture.push(chunk(0.5)); // first syllable, before the detector fires
  capture.start();
  capture.push(chunk(0.25));
  const wav = new DataView(capture.stop());

  const samples = wav.getUint32(40, true) / 2;
  assert.equal(samples, 1600 * 7, '600 ms kept (500 of silence and the syllable), then the rest, at 16 kHz');
  assert.equal(wav.getInt16(44, true), 0);
  assert.equal(wav.getInt16(44 + 1600 * 5 * 2, true), Math.trunc(0.5 * 0x7fff), 'the syllable is in the recording');
});

test('a new recording does not carry the previous one', () => {
  const capture = new SpeechCapture(48000, 600);
  capture.start();
  capture.push(chunk(0.5));
  capture.stop();
  capture.push(chunk(0));
  capture.start();
  const wav = new DataView(capture.stop());
  assert.equal(wav.getUint32(40, true) / 2, 1600);
  assert.equal(wav.getInt16(44, true), 0);
});

test('WAV header for 16 kHz mono 16-bit, also from 44.1 kHz', () => {
  const wav = new DataView(encodeWav([new Float32Array(44100).fill(-1)], 44100));
  const text = (at) => String.fromCharCode(...[0, 1, 2, 3].map((i) => wav.getUint8(at + i)));
  assert.equal(text(0), 'RIFF');
  assert.equal(text(8), 'WAVE');
  assert.equal(wav.getUint16(22, true), 1);
  assert.equal(wav.getUint32(24, true), 16000);
  assert.equal(wav.getUint32(40, true), 16000 * 2);
  assert.equal(wav.getInt16(44, true), -0x8000);
});
