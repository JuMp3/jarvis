// Run with: node --test src/test/js/vad.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { VoiceDetector } from '../../main/resources/static/vad.js';

/** Feeds [level, durationMs] steps every 30 ms and collects the events with their time. */
function run(detector, steps, sensitivity = 1) {
  const events = [];
  let now = 0;
  for (const [level, duration] of steps) {
    for (let t = 0; t < duration; t += 30, now += 30) {
      const event = detector.update(level, now, sensitivity);
      if (event) events.push([event, now]);
    }
  }
  return events;
}

test('a phrase between silences starts and ends once', () => {
  const events = run(new VoiceDetector(), [[0.01, 1000], [0.3, 1200], [0.01, 1500]]);
  assert.deepEqual(events.map(([e]) => e), ['start', 'end']);
  assert.ok(events[0][1] >= 1150 && events[0][1] <= 1250, 'starts about 150 ms after the voice');
});

test('a short noise is not speech', () => {
  assert.deepEqual(run(new VoiceDetector(), [[0.01, 1000], [0.4, 90], [0.01, 1000]]), []);
});

test('a short pause inside the phrase does not end it', () => {
  const events = run(new VoiceDetector(), [[0.01, 600], [0.3, 800], [0.01, 400], [0.3, 800], [0.01, 1500]]);
  assert.deepEqual(events.map(([e]) => e), ['start', 'end']);
});

test('the echo of Jarvis does not trigger, a louder voice does', () => {
  assert.deepEqual(run(new VoiceDetector(), [[0.01, 600], [0.1, 1500]], 2.5), []);
  assert.deepEqual(run(new VoiceDetector(), [[0.01, 600], [0.35, 600]], 2.5).map(([e]) => e), ['start']);
});

test('a steady noisy room raises the floor instead of looking like speech', () => {
  const detector = new VoiceDetector();
  run(detector, [[0.05, 3000]]);
  assert.deepEqual(run(detector, [[0.05, 3000]]), []);
  assert.deepEqual(run(detector, [[0.4, 600]]).map(([e]) => e), ['start']);
});
