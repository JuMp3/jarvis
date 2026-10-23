// Run with: node --test src/test/js/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseMarkdown, parseInline, weatherIcon } from '../../main/resources/static/markdown.js';

const types = (text) => parseMarkdown(text).map((block) => block.type);

test('plain text stays one paragraph per block of lines', () => {
  const blocks = parseMarkdown('Ciao.\nCome stai?\n\nSecondo paragrafo.');
  assert.deepEqual(blocks.map((b) => b.type), ['paragraph', 'paragraph']);
  assert.deepEqual(blocks[0].inline, [{ type: 'text', text: 'Ciao.\nCome stai?' }]);
});

test('headings, rules, quotes and code', () => {
  assert.deepEqual(types('## Titolo\n---\n> citazione\n> ancora\n```js\nlet a = 1;\n```'),
    ['heading', 'rule', 'quote', 'code']);
  const [heading, , quote, code] = parseMarkdown('## Titolo ##\n---\n> citazione\n> ancora\n```js\nlet a = 1;\n```');
  assert.equal(heading.level, 2);
  assert.deepEqual(heading.inline, [{ type: 'text', text: 'Titolo' }]);
  assert.deepEqual(quote.inline, [{ type: 'text', text: 'citazione\nancora' }]);
  assert.deepEqual(code, { type: 'code', lang: 'js', text: 'let a = 1;' });
});

test('lists, numbered from their first number, with nested items and continuation lines', () => {
  const [list] = parseMarkdown('3. Primo\n   - dettaglio\n   - altro\n4. Secondo\n   che continua');
  assert.equal(list.ordered, true);
  assert.equal(list.start, 3);
  assert.equal(list.items.length, 2);
  assert.equal(list.items[0].list.ordered, false);
  assert.equal(list.items[0].list.items.length, 2);
  assert.deepEqual(list.items[1].inline, [{ type: 'text', text: 'Secondo\nche continua' }]);
});

test('a list ends at a blank line or a line that is not part of it', () => {
  assert.deepEqual(types('- a\n- b\n\nTesto'), ['list', 'paragraph']);
  assert.deepEqual(types('- a\n## Dopo'), ['list', 'heading']);
});

test('tables with header and rows', () => {
  const [table] = parseMarkdown('| Città | Gradi |\n|---|:--:|\n| Roma | 21 |\n| Milano | **18** |\n\nFine');
  assert.equal(table.type, 'table');
  assert.deepEqual(table.header.map((cell) => cell[0].text), ['Città', 'Gradi']);
  assert.equal(table.rows.length, 2);
  assert.deepEqual(table.rows[1][1], [{ type: 'strong', children: [{ type: 'text', text: '18' }] }]);
});

test('inline: strong, em, code, links; only http(s) addresses become links', () => {
  assert.deepEqual(parseInline('**Nota** su *questo* e `x`'), [
    { type: 'strong', children: [{ type: 'text', text: 'Nota' }] },
    { type: 'text', text: ' su ' },
    { type: 'em', children: [{ type: 'text', text: 'questo' }] },
    { type: 'text', text: ' e ' },
    { type: 'code', text: 'x' },
  ]);
  assert.deepEqual(parseInline('[sito](https://example.com/a) e [no](javascript:alert(1))').slice(0, 2), [
    { type: 'link', text: 'sito', href: 'https://example.com/a' },
    { type: 'text', text: ' e [no](javascript:alert(1))' },
  ]);
  assert.deepEqual(parseInline('Vedi https://example.com.'), [
    { type: 'text', text: 'Vedi ' },
    { type: 'link', text: 'https://example.com', href: 'https://example.com' },
    { type: 'text', text: '.' },
  ]);
  assert.deepEqual(parseInline('2 * 3 * 4'), [{ type: 'text', text: '2 * 3 * 4' }]);
});

test('cards: JSON in a ```card block, pending while it streams, code if not valid', () => {
  const [card] = parseMarkdown('```card\n{"type":"weather","place":"Roma"}\n```');
  assert.deepEqual(card, { type: 'card', card: { type: 'weather', place: 'Roma' } });
  assert.deepEqual(types('Ecco:\n```card\n{"type":"wea'), ['paragraph', 'pending-card']);
  assert.deepEqual(types('```card\n{non json}\n```'), ['code']);
  assert.deepEqual(types('```card\n[1, 2]\n```'), ['code']);
});

test('weather icons from Italian or English words', () => {
  assert.equal(weatherIcon('Sereno'), '☀️');
  assert.equal(weatherIcon('poco nuvoloso'), '⛅');
  assert.equal(weatherIcon('Nuvoloso'), '☁️');
  assert.equal(weatherIcon('Pioggia debole'), '🌧️');
  assert.equal(weatherIcon('Temporale con pioggia'), '⛈️');
  assert.equal(weatherIcon('light snow'), '❄️');
  assert.equal(weatherIcon('prevalentemente soleggiato'), '☀️');
  assert.equal(weatherIcon('ventoso'), '💨');
  assert.equal(weatherIcon('nuvoloso, possibile pioviggine'), '🌧️');
  assert.equal(weatherIcon(undefined), '🌡️');
});

test('the browser voice skips cards, code and tables, as the server does', async () => {
  const { SpokenText, forSpeech } = await import('../../main/resources/static/markdown.js');
  const answer = 'Domani sole.\n```card\n{"type":"weather"}\n```\n| a | b |\n|---|---|\nPorta l\'**ombrello**.';
  for (const size of [1, 4, 100]) {
    const filter = new SpokenText();
    let out = '';
    for (let i = 0; i < answer.length; i += size) out += filter.append(answer.slice(i, i + size));
    out += filter.flush();
    assert.equal(forSpeech(out), "Domani sole. Porta l'ombrello.");
  }
});
