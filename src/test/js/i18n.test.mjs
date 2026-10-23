// Run with: node --test src/test/js/i18n.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { messages, LANGUAGES, t, setLanguage, language } from '../../main/resources/static/i18n.js';

const placeholders = (text) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

test('every key exists in every language, with the same placeholders', () => {
  const keys = new Set(LANGUAGES.flatMap((code) => Object.keys(messages[code])));
  for (const code of LANGUAGES) {
    for (const key of keys) {
      assert.ok(messages[code][key], `${code} has no "${key}"`);
      assert.deepEqual(placeholders(messages[code][key]), placeholders(messages.it[key]), `${code} "${key}"`);
    }
  }
});

test('fills the placeholders in the chosen language', () => {
  setLanguage('en');
  assert.equal(language(), 'en');
  assert.equal(t('task.running', { time: '1:05' }), 'at work for 1:05');
  setLanguage('it');
  assert.equal(t('task.running', { time: '1:05' }), 'al lavoro da 1:05');
});

test('an unknown language falls back to English, an unknown key to itself', () => {
  setLanguage('fr');
  assert.equal(language(), 'en');
  assert.equal(t('no.such.key'), 'no.such.key');
  setLanguage('it');
});
