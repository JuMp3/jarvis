// Wizard (first start) and Settings: the same form, one tab per group of the catalog (SettingsCatalog).
// The wizard walks the tabs in order; Settings lets you jump between them. Values edited in a tab are kept
// while moving to another one. Secrets never come back from the server: an empty field keeps the saved one.
// Labels and help come from the server in the user's language; choosing another language in the wizard asks
// for them again in that one.

import { t, language, setLanguage } from './i18n.js';

const MAIL = 'JARVIS_MAIL';
const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const LANGUAGE = 'jarvis.language';
const MICROSOFT = new Set(['outlook', 'microsoft365']);

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text != null) element.textContent = text;
  return element;
}

/** Links in server messages (e.g. the Microsoft sign-in page) become clickable, through the DOM only. */
function withLinks(element, text) {
  element.replaceChildren();
  let last = 0;
  for (const match of text.matchAll(/https?:\/\/[^\s)\]>"]+/g)) {
    element.append(text.slice(last, match.index));
    const link = node('a', null, match[0]);
    link.href = match[0];
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    element.append(link);
    last = match.index + match[0].length;
  }
  element.append(text.slice(last));
  return element;
}

export function createSettings({ send }) {
  const dialog = document.getElementById('settings');
  const form = document.getElementById('settings-form');
  const title = document.getElementById('settings-title');
  const intro = document.getElementById('settings-intro');
  const tabs = document.getElementById('settings-tabs');
  const body = document.getElementById('settings-body');
  const status = document.getElementById('settings-status');
  const back = document.getElementById('settings-back');
  const next = document.getElementById('settings-next');
  const save = document.getElementById('settings-save');
  const later = document.getElementById('settings-later');

  let settings = [];
  let groups = [];
  let wizard = false;
  let step = 0;
  let restarting = false;
  let saved = false;
  let openedIn = language();
  const fields = new Map(); // name -> { read: () => value | undefined, clear?: () => boolean }

  /** The values of every tab seen so far are kept while moving between tabs. */
  const pending = new Map();
  const pendingClears = new Set();

  /** What a field shows: the value edited in another tab, if any, else the one in use. */
  const shown = (setting) => (pending.has(setting.name) ? pending.get(setting.name) : setting.value);

  /** A setting only shows while its conditions hold (SettingsCatalog.Condition): a key only for its provider. */
  const valueOf = (name) => (pending.has(name) ? pending.get(name) : settings.find((s) => s.name === name)?.value);
  const visible = (setting) => (setting.when ?? []).every((condition) => condition.settings.some((name) => {
    const value = valueOf(name);
    return Array.isArray(value) ? value.some((v) => condition.values.includes(v)) : value != null && condition.values.includes(String(value));
  }));
  let shownFields = []; // [wrapper, setting] of the open tab
  let limitedChoices = []; // [inputs, setting] of choices offered only if chosen elsewhere (choicesFrom)

  /** A list, also when a multiple choice comes as text ("anthropic,openai") from the configuration. */
  const listOf = (value) => (Array.isArray(value) ? value : String(value ?? '').split(',').map((v) => v.trim()).filter(Boolean));

  function applyVisibility() {
    for (const [wrapper, setting] of shownFields) wrapper.hidden = !visible(setting);
    for (const [inputs, setting] of limitedChoices) {
      const offered = listOf(valueOf(setting.choicesFrom));
      inputs.forEach((input) => { input.closest('.setting-choice').hidden = !offered.includes(input.value); });
      // The chosen one is no longer offered: the first one that is takes its place
      if (!offered.includes(inputs.find((input) => input.checked)?.value)) {
        const first = inputs.find((input) => offered.includes(input.value));
        if (first) {
          first.checked = true;
          pending.set(setting.name, first.value);
        }
      }
    }
  }

  function field(setting) {
    const wrapper = node('div', `setting kind-${setting.kind}`); // not setting-<kind>: .setting-choice is an option
    wrapper.dataset.name = setting.name;
    wrapper.hidden = !visible(setting);
    shownFields.push([wrapper, setting]);
    const id = `setting-${setting.name.replace(/[^a-zA-Z0-9]/g, '-')}`;
    const label = node('label', 'setting-label', setting.label);
    label.htmlFor = id;
    wrapper.append(label);
    const help = node('p', 'setting-help', setting.help);
    help.id = `${id}-help`;

    switch (setting.kind) {
      case 'secret': {
        const input = node('input');
        Object.assign(input, { id, type: 'password', autocomplete: 'off', spellcheck: false });
        input.placeholder = setting.set ? '••••••••••••' : t('settings.secret.placeholder');
        input.value = pending.get(setting.name) ?? '';
        const setText = t('settings.secret.set', { origin: setting.origin ? t(`settings.origin.${setting.origin}`) : '' });
        const state = node('p', setting.set ? 'setting-state set' : 'setting-state', setting.set ? setText : t('settings.secret.unset'));
        state.id = `${id}-state`;
        input.setAttribute('aria-describedby', `${state.id} ${help.id}`);
        const row = node('div', 'setting-row');
        // Show / hide: the value in use is asked to the server only when the user wants to see it
        const eye = node('button', 'eye');
        eye.type = 'button';
        eye.setAttribute('aria-label', t('settings.secret.show'));
        eye.setAttribute('aria-pressed', 'false');
        eye.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5C6.5 5 2.7 9.4 1.5 12c1.2 2.6 5 7 10.5 7s9.3-4.4 10.5-7C21.3 9.4 17.5 5 12 5Zm0 11a4 4 0 1 1 0-8 4 4 0 0 1 0 8Zm0-2.2a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6Z"/></svg>';
        eye.addEventListener('click', () => {
          const show = input.type === 'password';
          input.type = show ? 'text' : 'password';
          eye.setAttribute('aria-pressed', String(show));
          eye.setAttribute('aria-label', t(show ? 'settings.secret.hide' : 'settings.secret.show'));
          if (show && !input.value && setting.set) send({ type: 'settings-reveal', name: setting.name });
        });
        input.dataset.secret = setting.name;
        const masked = node('div', 'secret-field');
        masked.append(input, eye);
        row.append(masked);
        const result = node('p', 'setting-result');
        result.setAttribute('aria-live', 'polite');
        result.dataset.test = setting.name;
        if (setting.test) {
          const verify = node('button', 'secondary small', t('settings.verify'));
          verify.type = 'button';
          verify.addEventListener('click', () => {
            result.textContent = t('settings.verifying');
            collect(); // the check may need what is not saved yet: the Azure region, the IMAP address
            send({ type: 'settings-test', name: setting.name, value: input.value, values: Object.fromEntries(pending) });
          });
          row.append(verify);
        }
        let removing = pendingClears.has(setting.name);
        if (setting.set && setting.origin === 'settings') {
          const remove = node('button', 'secondary small');
          remove.type = 'button';
          const update = () => {
            remove.textContent = t(removing ? 'settings.secret.undo' : 'settings.secret.remove');
            remove.setAttribute('aria-pressed', String(removing));
            state.textContent = removing ? t('settings.secret.removing') : setText;
            state.classList.toggle('removing', removing);
          };
          remove.addEventListener('click', () => { removing = !removing; update(); });
          update();
          row.append(remove);
        }
        wrapper.append(row, state, help, result);
        fields.set(setting.name, { read: () => input.value || undefined, clear: () => removing });
        break;
      }
      case 'list': {
        const area = node('textarea');
        Object.assign(area, { id, rows: 4, spellcheck: false });
        const value = shown(setting);
        area.value = Array.isArray(value) ? value.join('\n') : (value ?? '');
        area.setAttribute('aria-describedby', help.id);
        wrapper.append(area, help);
        fields.set(setting.name, { read: () => area.value });
        break;
      }
      case 'apps': {
        // One row per app: name, program, arguments; an empty row is ignored when saving
        label.removeAttribute('for');
        const rows = node('div', 'apps');
        const addRow = (app = {}) => {
          const row = node('div', 'app-row');
          const inputs = [['name', t('settings.apps.name'), 'Visual Studio Code'],
            ['program', t('settings.apps.program'), 'C:/…/Code.exe'],
            ['arguments', t('settings.apps.arguments'), t('settings.apps.optional')]].map(([key, text, example]) => {
            const input = node('input');
            Object.assign(input, { type: 'text', value: app[key] ?? '', placeholder: example, spellcheck: false });
            input.dataset.key = key;
            input.setAttribute('aria-label', text);
            return input;
          });
          const remove = node('button', 'secondary small', t('settings.apps.remove'));
          remove.type = 'button';
          remove.addEventListener('click', () => row.remove());
          row.append(...inputs, remove);
          rows.append(row);
        };
        const head = node('div', 'app-row app-head');
        head.setAttribute('aria-hidden', 'true');
        head.append(node('span', null, t('settings.apps.name')), node('span', null, t('settings.apps.program')),
          node('span', null, t('settings.apps.arguments')));
        (shown(setting) ?? []).forEach(addRow);
        const add = node('button', 'secondary small', t('settings.apps.add'));
        add.type = 'button';
        add.addEventListener('click', () => addRow());
        wrapper.append(help, head, rows, add);
        fields.set(setting.name, {
          read: () => [...rows.children].map((row) => Object.fromEntries(
            [...row.querySelectorAll('input')].map((input) => [input.dataset.key, input.value.trim()]))),
        });
        break;
      }
      case 'agents': {
        // One row per external agent: name, address, token (never shown: empty keeps the saved one)
        label.removeAttribute('for');
        const rows = node('div', 'apps');
        const addRow = (agent = {}) => {
          const row = node('div', 'app-row agent-row');
          const inputs = [['name', t('settings.agents.name'), 'Meteo', 'text'],
            ['url', t('settings.agents.url'), 'https://agente.example.com', 'url'],
            ['token', t('settings.agents.token'), agent.hasToken ? '••••••••' : t('settings.apps.optional'), 'password']]
            .map(([key, text, example, type]) => {
              const input = node('input');
              Object.assign(input, { type, value: key === 'token' ? '' : agent[key] ?? '', placeholder: example, spellcheck: false });
              input.dataset.key = key;
              input.setAttribute('aria-label', text);
              return input;
            });
          const remove = node('button', 'secondary small', t('settings.apps.remove'));
          remove.type = 'button';
          remove.addEventListener('click', () => row.remove());
          row.append(...inputs, remove);
          if (agent.description) row.append(node('p', 'setting-help agent-description', agent.description));
          rows.append(row);
        };
        (shown(setting) ?? []).forEach(addRow);
        const add = node('button', 'secondary small', t('settings.agents.add'));
        add.type = 'button';
        add.addEventListener('click', () => addRow());
        wrapper.append(help, rows, add);
        fields.set(setting.name, {
          read: () => [...rows.children].map((row) => Object.fromEntries(
            [...row.querySelectorAll('input')].map((input) => [input.dataset.key, input.value.trim()]))),
        });
        break;
      }
      case 'routines': {
        // One block per routine: name, time, days, instructions, on/off; an empty one is ignored when saving
        label.removeAttribute('for');
        const list = node('div', 'routines');
        const addRoutine = (routine = { days: DAYS, enabled: true }) => {
          const box = node('fieldset', 'routine');
          box.dataset.id = routine.id ?? '';
          const name = Object.assign(node('input'), { type: 'text', value: routine.name ?? '', placeholder: t('settings.routines.namePlaceholder') });
          name.dataset.key = 'name';
          name.setAttribute('aria-label', t('settings.routines.name'));
          const time = Object.assign(node('input'), { type: 'time', value: routine.time ?? '08:00' });
          time.dataset.key = 'time';
          time.setAttribute('aria-label', t('settings.routines.time'));
          const enabled = Object.assign(node('input'), { type: 'checkbox', checked: routine.enabled !== false });
          enabled.dataset.key = 'enabled';
          const on = node('label', 'routine-on');
          on.append(enabled, node('span', null, t('settings.routines.enabled')));
          const head = node('div', 'routine-head');
          head.append(name, time, on);
          const days = node('div', 'routine-days');
          days.setAttribute('role', 'group');
          days.setAttribute('aria-label', t('settings.routines.days'));
          for (const day of DAYS) {
            const option = node('label', 'routine-day');
            const check = Object.assign(node('input'), { type: 'checkbox', value: day, checked: (routine.days ?? []).includes(day) });
            option.append(check, node('span', null, t(`days.${day}`)));
            days.append(option);
          }
          const instructions = Object.assign(node('textarea'), { rows: 3, value: routine.instructions ?? '', placeholder: t('settings.routines.instructionsPlaceholder') });
          instructions.dataset.key = 'instructions';
          instructions.setAttribute('aria-label', t('settings.routines.instructions'));
          const remove = node('button', 'secondary small', t('settings.apps.remove'));
          remove.type = 'button';
          remove.addEventListener('click', () => box.remove());
          box.append(head, days, instructions, remove);
          list.append(box);
        };
        (shown(setting) ?? []).forEach(addRoutine);
        const add = node('button', 'secondary small', t('settings.routines.add'));
        add.type = 'button';
        add.addEventListener('click', () => addRoutine());
        wrapper.append(help, list, add);
        fields.set(setting.name, {
          read: () => [...list.children].map((box) => ({
            id: box.dataset.id,
            name: box.querySelector('[data-key="name"]').value.trim(),
            time: box.querySelector('[data-key="time"]').value,
            days: [...box.querySelectorAll('.routine-day input:checked')].map((input) => input.value),
            instructions: box.querySelector('[data-key="instructions"]').value.trim(),
            enabled: box.querySelector('[data-key="enabled"]').checked,
          })),
        });
        break;
      }
      case 'choice':
      case 'multi_choice': {
        const group = node('div', 'setting-choices');
        group.setAttribute('role', setting.kind === 'choice' ? 'radiogroup' : 'group');
        group.setAttribute('aria-labelledby', `${id}-label`);
        group.setAttribute('aria-describedby', help.id);
        label.id = `${id}-label`;
        label.removeAttribute('for');
        const value = shown(setting);
        const current = Array.isArray(value) ? value : [value];
        const preview = node('p', 'setting-result');
        preview.setAttribute('aria-live', 'polite');
        preview.dataset.preview = setting.name;
        // Each option is a tile: its name, and right below it what it means
        const inputs = setting.choices.map((choice) => {
          const option = node('label', 'setting-choice');
          const input = node('input');
          Object.assign(input, {
            type: setting.kind === 'choice' ? 'radio' : 'checkbox', name: id, value: choice.value,
            checked: current.includes(choice.value),
          });
          const text = node('span', 'choice-text');
          text.append(node('span', 'choice-label', choice.label));
          if (choice.description) text.append(node('span', 'choice-description', choice.description));
          option.append(input, text);
          if (setting.test === 'voice') option.append(previewButton(setting, choice, preview));
          group.append(option);
          return input;
        });
        wrapper.append(help, group);
        if (setting.test === 'voice') wrapper.append(preview);
        fields.set(setting.name, {
          read: () => (setting.kind === 'choice'
            ? inputs.find((i) => i.checked)?.value
            : inputs.filter((i) => i.checked).map((i) => i.value)),
        });
        if (setting.name === MAIL) wrapper.append(accountControls(setting.value));
        if (setting.choicesFrom) limitedChoices.push([inputs, setting]);
        // Choosing a provider shows the fields it needs (its key...), and hides the others
        inputs.forEach((input) => input.addEventListener('change', () => { collect(); applyVisibility(); }));
        if (setting.name === LANGUAGE) {
          // The rest of the wizard, and the page, in the language just chosen; closing without saving goes back
          inputs.forEach((input) => input.addEventListener('change', () => {
            collect();
            setLanguage(input.value);
            send({ type: 'settings-get', value: input.value });
          }));
        }
        break;
      }
      default: {
        const input = node('input');
        Object.assign(input, { id, type: 'text', value: shown(setting) ?? '', spellcheck: false });
        input.setAttribute('aria-describedby', help.id);
        wrapper.append(input, help);
        fields.set(setting.name, { read: () => input.value });
      }
    }
    return wrapper;
  }

  /** ▶ on a voice: a sample, with the key and region typed so far, before choosing and saving. */
  function previewButton(setting, choice, status) {
    const button = node('button', 'preview', '▶');
    button.type = 'button';
    button.setAttribute('aria-label', t('settings.preview.play', { voice: choice.label }));
    button.addEventListener('click', (event) => {
      event.preventDefault(); // inside the tile's label: hearing a voice does not choose it
      event.stopPropagation();
      collect();
      status.textContent = t('settings.preview.loading');
      send({ type: 'settings-preview', name: setting.name, value: choice.value, values: Object.fromEntries(pending) });
    });
    return button;
  }

  /** Sign-in of the Microsoft account: only once the provider is saved and Jarvis has restarted with it. */
  function accountControls(provider) {
    const box = node('div', 'account');
    if (!MICROSOFT.has(provider)) return box;
    const login = node('button', 'secondary small', t('settings.account.login'));
    const verify = node('button', 'secondary small', t('settings.account.verify'));
    login.type = verify.type = 'button';
    const message = node('p', 'setting-result account-message');
    message.setAttribute('aria-live', 'polite');
    login.addEventListener('click', () => { message.textContent = t('settings.account.asking'); send({ type: 'account-login', server: 'mail' }); });
    verify.addEventListener('click', () => { message.textContent = t('settings.verifying'); send({ type: 'account-verify', server: 'mail' }); });
    box.append(login, verify, message);
    return box;
  }

  function render() {
    groups = [...new Set(settings.map((s) => s.group))];
    step = Math.max(0, Math.min(step, groups.length - 1));
    tabs.replaceChildren(...groups.map((group, index) => {
      const tab = node('button', 'settings-tab', group);
      Object.assign(tab, { type: 'button', id: `settings-tab-${index}` });
      tab.setAttribute('role', 'tab');
      tab.setAttribute('aria-selected', String(index === step));
      tab.setAttribute('aria-controls', 'settings-body');
      tab.tabIndex = index === step ? 0 : -1;
      tab.addEventListener('click', () => go(index));
      return tab;
    }));

    body.replaceChildren();
    body.setAttribute('aria-labelledby', `settings-tab-${step}`);
    fields.clear();
    shownFields = [];
    limitedChoices = [];
    const section = node('section', 'settings-group');
    settings.filter((s) => s.group === groups[step]).forEach((s) => section.append(field(s)));
    body.append(section);
    applyVisibility();
    body.scrollTop = 0;

    title.textContent = t(wizard ? 'settings.wizardTitle' : 'settings.title');
    intro.textContent = wizard
      ? t('settings.wizardIntro', { step: step + 1, total: groups.length })
      : t('settings.intro');
    back.hidden = !wizard || step === 0;
    next.hidden = !wizard || step === groups.length - 1;
    save.hidden = wizard && step < groups.length - 1;
    later.hidden = !wizard;
    status.textContent = '';
  }

  function go(index) {
    collect();
    step = index;
    render();
  }

  function collect() {
    for (const [name, f] of fields) {
      const value = f.read();
      if (value !== undefined) pending.set(name, value);
      if (f.clear?.()) pendingClears.add(name);
      else pendingClears.delete(name);
    }
  }

  // Arrows move between tabs, as in any tab list
  tabs.addEventListener('keydown', (event) => {
    const move = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
    if (!move || groups.length === 0) return;
    event.preventDefault();
    go((step + move + groups.length) % groups.length);
    tabs.children[step].focus();
  });
  back.addEventListener('click', () => go(step - 1));
  next.addEventListener('click', () => go(step + 1));
  later.addEventListener('click', () => { sessionStorage.setItem('jarvis.setupLater', '1'); dialog.close(); });
  // Not saved: the page goes back to its language (after a save, the next hello brings the new one)
  dialog.addEventListener('close', () => {
    if (!saved) setLanguage(openedIn);
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    collect();
    status.textContent = t('settings.saving');
    send({ type: 'settings-save', values: Object.fromEntries(pending), cleared: [...pendingClears] });
  });

  return {
    open(asWizard) {
      wizard = asWizard;
      step = 0;
      saved = false;
      openedIn = language();
      pending.clear();
      pendingClears.clear();
      send({ type: 'settings-get' });
      if (!dialog.open) dialog.showModal();
    },
    /** Called with the hello of every (re)connection; true right after a restart that applied new settings. */
    onHello(msg) {
      if (restarting) {
        restarting = false;
        if (dialog.open) dialog.close();
        return true; // the new settings are in use
      }
      if (msg.setupRequired && !sessionStorage.getItem('jarvis.setupLater')) this.open(true);
      return false;
    },
    handle(msg) {
      switch (msg.type) {
        case 'settings':
          settings = msg.settings;
          render();
          return true;
        case 'settings-saved':
          restarting = msg.restarting;
          saved = true;
          status.textContent = t('settings.saved');
          return true;
        case 'settings-secret': {
          const input = body.querySelector(`[data-secret="${CSS.escape(msg.name)}"]`);
          if (input && !input.value) input.value = msg.value;
          return true;
        }
        case 'settings-error':
          status.textContent = msg.message;
          return true;
        case 'settings-preview': {
          const target = body.querySelector(`[data-preview="${CSS.escape(msg.name)}"]`);
          if (target) target.textContent = msg.data ? '' : msg.message;
          if (msg.data) new Audio(`data:${msg.mime};base64,${msg.data}`).play();
          return true;
        }
        case 'settings-test-result': {
          const target = body.querySelector(`[data-test="${CSS.escape(msg.name)}"]`);
          if (target) target.textContent = (msg.ok ? '✓ ' : '✗ ') + msg.message;
          return true;
        }
        case 'account-result': {
          const target = body.querySelector('.account-message');
          // The mail server answers with JSON: show its message, and who is signed in
          let text = msg.message;
          try {
            const data = JSON.parse(text);
            text = data.userData
              ? `${data.message}: ${data.userData.displayName} (${data.userData.userPrincipalName})`
              : data.message ?? text;
          } catch { /* plain text */ }
          if (target) withLinks(target, text);
          return true;
        }
        default:
          return false;
      }
    },
  };
}
