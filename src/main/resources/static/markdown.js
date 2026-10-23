// The Markdown of Jarvis' written answers, parsed into plain objects. app.js turns them into DOM nodes, never
// HTML: model and agent output is untrusted. Pure logic, tested in src/test/js.
//
// Blocks: heading, paragraph, list (nested by indentation), code, table, quote, rule, and cards: a fenced
// block ```card with a JSON object, e.g. the weather (see AgentService.WRITTEN for what the model is told).

const LIST_ITEM = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
const TABLE_SEPARATOR = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

export function parseMarkdown(text) {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const blocks = [];
  let paragraph = [];
  const flush = () => {
    if (paragraph.length) blocks.push({ type: 'paragraph', inline: parseInline(paragraph.join('\n')) });
    paragraph = [];
  };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let m;
    if ((m = line.match(/^\s*```\s*([\w-]*)\s*$/))) {
      flush();
      const body = [];
      let closed = false;
      while (++i < lines.length) {
        if (/^\s*```\s*$/.test(lines[i])) {
          closed = true;
          break;
        }
        body.push(lines[i]);
      }
      blocks.push(fenced(m[1].toLowerCase(), body.join('\n'), closed));
    } else if (!line.trim()) {
      flush();
    } else if ((m = line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/))) {
      flush();
      blocks.push({ type: 'heading', level: m[1].length, inline: parseInline(m[2]) });
    } else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      flush();
      blocks.push({ type: 'rule' });
    } else if (LIST_ITEM.test(line)) {
      flush();
      const entries = [];
      for (; i < lines.length && lines[i].trim(); i++) {
        const item = lines[i].match(LIST_ITEM);
        if (item) {
          entries.push({ indent: item[1].length, ordered: /\d/.test(item[2]), number: parseInt(item[2], 10), text: item[3] });
        } else if (/^\s/.test(lines[i])) {
          entries.at(-1).text += '\n' + lines[i].trim(); // continuation of the item above
        } else {
          break;
        }
      }
      i--;
      blocks.push(nest(entries));
    } else if (line.includes('|') && TABLE_SEPARATOR.test(lines[i + 1] ?? '')) {
      flush();
      const header = cells(line);
      const rows = [];
      for (i += 2; i < lines.length && lines[i].includes('|') && lines[i].trim(); i++) rows.push(cells(lines[i]));
      i--;
      blocks.push({ type: 'table', header, rows });
    } else if ((m = line.match(/^\s*>\s?(.*)$/))) {
      flush();
      const quoted = [m[1]];
      while (i + 1 < lines.length && (m = lines[i + 1].match(/^\s*>\s?(.*)$/))) {
        quoted.push(m[1]);
        i++;
      }
      blocks.push({ type: 'quote', inline: parseInline(quoted.join('\n')) });
    } else {
      paragraph.push(line);
    }
  }
  flush();
  return blocks;
}

/** A card while its block is still streaming is "pending"; one that is not valid JSON stays code. */
function fenced(lang, body, closed) {
  if (lang !== 'card') return { type: 'code', lang, text: body };
  if (!closed) return { type: 'pending-card' };
  try {
    const card = JSON.parse(body);
    if (card && typeof card === 'object' && typeof card.type === 'string') return { type: 'card', card };
  } catch {
    // shown as code below
  }
  return { type: 'code', lang, text: body };
}

/** Items deeper than the first one belong to the item above them. */
function nest(entries) {
  const list = { type: 'list', ordered: entries[0].ordered, start: entries[0].number || 1, items: [] };
  const base = entries[0].indent;
  let children = [];
  const close = () => {
    if (children.length) list.items.at(-1).list = nest(children);
    children = [];
  };
  for (const entry of entries) {
    if (entry.indent > base && list.items.length) {
      children.push(entry);
    } else {
      close();
      list.items.push({ inline: parseInline(entry.text) });
    }
  }
  close();
  return list;
}

function cells(line) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => parseInline(cell.trim()));
}

const INLINE = /`([^`]+)`|\*\*(.+?)\*\*|\*(?![\s*])(.+?)\*|\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)|(https?:\/\/[^\s)\]>"]+)/g;

/** Text, **strong**, *em*, `code`, [links](https://...) and bare http(s) addresses. */
export function parseInline(text) {
  const nodes = [];
  let last = 0;
  const plain = (value) => value && nodes.push({ type: 'text', text: value });
  for (const m of text.matchAll(INLINE)) {
    plain(text.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[1] != null) nodes.push({ type: 'code', text: m[1] });
    else if (m[2] != null) nodes.push({ type: 'strong', children: parseInline(m[2]) });
    else if (m[3] != null) nodes.push({ type: 'em', children: parseInline(m[3]) });
    else if (m[4] != null) nodes.push({ type: 'link', text: m[4], href: m[5] });
    else {
      const url = m[6].replace(/[.,;:!?]+$/, ''); // the full stop after an address is not part of it
      nodes.push({ type: 'link', text: url, href: url });
      last -= m[6].length - url.length;
    }
  }
  plain(text.slice(last));
  return nodes;
}

const WEATHER_ICONS = [
  [/temporal|thunder|storm/, '⛈️'],
  [/neve|nevic|snow/, '❄️'],
  [/grandin|hail/, '🌨️'],
  [/pioggia|piov|rovesc|acquazz|rain|shower|drizzle/, '🌧️'],
  [/nebbia|foschia|fog|mist|haze/, '🌫️'],
  [/poco nuvol|parzial|variabil|sole e nuvol|partly|few clouds/, '⛅'],
  [/nuvol|copert|cloud|overcast/, '☁️'],
  [/\bvent(o|os[oa]|i)\b|wind/, '💨'], // whole words: "prevalentemente" is not wind
  [/seren|sole|soleggiat|limpid|clear|sun/, '☀️'],
];

/** An icon for a weather condition in words, Italian or English; a thermometer if nothing matches. */
export function weatherIcon(condition) {
  const words = String(condition ?? '').toLowerCase();
  return WEATHER_ICONS.find(([pattern]) => pattern.test(words))?.[1] ?? '🌡️';
}

/**
 * What of an answer the browser's voice reads, as it streams: fenced blocks (code, cards) and table rows are shown
 * only. The same rule as SpokenText on the server; a line is held back until its start tells which kind it is.
 */
export class SpokenText {
  constructor() {
    this.head = '';
    this.line = 'unknown';
    this.fence = false;
  }

  append(token) {
    let spoken = '';
    for (const c of token ?? '') {
      if (c === '\n') {
        if (this.line === 'unknown') this.decide(true);
        if (this.line === 'speak') spoken += `${this.head}\n`;
        this.head = '';
        this.line = 'unknown';
      } else if (this.line === 'speak') {
        spoken += c;
      } else if (this.line === 'unknown') {
        this.head += c;
        this.decide(false);
        if (this.line === 'speak') {
          spoken += this.head;
          this.head = '';
        }
      }
    }
    return spoken;
  }

  flush() {
    if (this.line === 'unknown') this.decide(true);
    const rest = this.line === 'speak' ? this.head : '';
    this.head = '';
    this.line = 'unknown';
    return rest;
  }

  decide(complete) {
    const start = this.head.trimStart();
    if (!start && !complete) return;
    if (start.startsWith('```')) {
      this.fence = !this.fence;
      this.line = 'skip';
    } else if (!complete && '```'.startsWith(start)) {
      this.line = 'unknown'; // may still open or close a fence
    } else if (this.fence || start.startsWith('|')) {
      this.line = 'skip';
    } else {
      this.line = 'speak';
    }
  }
}

/** Inline markup read aloud literally, removed: the same as SentenceChunker.forSpeech. */
export function forSpeech(text) {
  return text.replace(/[*_`#>|]/g, '').replace(/^\s*[-•]\s+/gm, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\s+/g, ' ').trim();
}
