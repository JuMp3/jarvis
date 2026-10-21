// Wizard (first start) and Settings: the same form, one step at a time or all sections together.
// Secrets never come back from the server: an empty field keeps the saved one.

const GROUPS = ['Modelli', 'Voce', 'Cartelle', 'Account'];
const ORIGIN = {
  settings: 'salvata nelle impostazioni',
  environment: 'da una variabile d\'ambiente, che ha la precedenza',
  file: 'da un file di configurazione (.env o application.yml)',
};

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
  const body = document.getElementById('settings-body');
  const status = document.getElementById('settings-status');
  const back = document.getElementById('settings-back');
  const next = document.getElementById('settings-next');
  const save = document.getElementById('settings-save');
  const later = document.getElementById('settings-later');

  let settings = [];
  let wizard = false;
  let step = 0;
  let restarting = false;
  const fields = new Map(); // name -> { read: () => value | undefined, clear?: () => boolean }

  function field(setting) {
    const wrapper = node('div', `setting setting-${setting.kind}`);
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
        input.placeholder = setting.set ? `Impostata, ${ORIGIN[setting.origin] ?? ''}. Lascia vuoto per non cambiarla.` : 'Non impostata';
        input.setAttribute('aria-describedby', help.id);
        const row = node('div', 'setting-row');
        // Show / hide: the value in use is asked to the server only when the user wants to see it
        const eye = node('button', 'eye');
        eye.type = 'button';
        eye.setAttribute('aria-label', 'Mostra la chiave');
        eye.setAttribute('aria-pressed', 'false');
        eye.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5C6.5 5 2.7 9.4 1.5 12c1.2 2.6 5 7 10.5 7s9.3-4.4 10.5-7C21.3 9.4 17.5 5 12 5Zm0 11a4 4 0 1 1 0-8 4 4 0 0 1 0 8Zm0-2.2a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6Z"/></svg>';
        eye.addEventListener('click', () => {
          const show = input.type === 'password';
          input.type = show ? 'text' : 'password';
          eye.setAttribute('aria-pressed', String(show));
          eye.setAttribute('aria-label', show ? 'Nascondi la chiave' : 'Mostra la chiave');
          if (show && !input.value && setting.set) send({ type: 'settings-reveal', name: setting.name });
        });
        input.dataset.secret = setting.name;
        const masked = node('div', 'secret-field');
        masked.append(input, eye);
        row.append(masked);
        let clear = null;
        if (setting.test) {
          const verify = node('button', 'secondary small', 'Verifica');
          verify.type = 'button';
          verify.addEventListener('click', () => {
            result.textContent = 'Verifico…';
            send({ type: 'settings-test', name: setting.name, value: input.value });
          });
          row.append(verify);
        }
        if (setting.set && setting.origin === 'settings') {
          const remove = node('label', 'setting-remove');
          clear = node('input');
          clear.type = 'checkbox';
          remove.append(clear, ' Rimuovi');
          row.append(remove);
        }
        const result = node('p', 'setting-result');
        result.setAttribute('aria-live', 'polite');
        result.dataset.test = setting.name;
        wrapper.append(row, help, result);
        fields.set(setting.name, { read: () => input.value || undefined, clear: () => clear?.checked ?? false });
        break;
      }
      case 'list': {
        const area = node('textarea');
        Object.assign(area, { id, rows: 3, spellcheck: false });
        area.value = (setting.value ?? []).join('\n');
        area.setAttribute('aria-describedby', help.id);
        wrapper.append(area, help);
        fields.set(setting.name, { read: () => area.value });
        break;
      }
      case 'choice':
      case 'multi_choice': {
        const group = node('div', 'setting-choices');
        group.setAttribute('role', setting.kind === 'choice' ? 'radiogroup' : 'group');
        group.setAttribute('aria-labelledby', `${id}-label`);
        label.id = `${id}-label`;
        label.removeAttribute('for');
        const current = Array.isArray(setting.value) ? setting.value : [setting.value];
        const inputs = setting.choices.map((choice) => {
          const option = node('label', 'setting-choice');
          const input = node('input');
          Object.assign(input, { type: setting.kind === 'choice' ? 'radio' : 'checkbox', name: id, value: choice, checked: current.includes(choice) });
          option.append(input, ` ${choice}`);
          group.append(option);
          return input;
        });
        wrapper.append(group, help);
        fields.set(setting.name, {
          read: () => (setting.kind === 'choice'
            ? inputs.find((i) => i.checked)?.value
            : inputs.filter((i) => i.checked).map((i) => i.value)),
        });
        if (setting.name === 'JARVIS_MAIL') wrapper.append(accountControls(current[0]));
        break;
      }
      default: {
        const input = node('input');
        Object.assign(input, { id, type: 'text', value: setting.value ?? '', spellcheck: false });
        input.setAttribute('aria-describedby', help.id);
        wrapper.append(input, help);
        fields.set(setting.name, { read: () => input.value });
      }
    }
    return wrapper;
  }

  /** Sign-in of the mail account: only once the provider is saved and Jarvis has restarted with it. */
  function accountControls(provider) {
    const box = node('div', 'account');
    if (provider !== 'outlook') return box;
    const login = node('button', 'secondary small', 'Collega l\'account Microsoft');
    const verify = node('button', 'secondary small', 'Ho fatto l\'accesso');
    login.type = verify.type = 'button';
    const message = node('p', 'setting-result account-message');
    message.setAttribute('aria-live', 'polite');
    login.addEventListener('click', () => { message.textContent = 'Chiedo il codice a Microsoft…'; send({ type: 'account-login', server: 'mail' }); });
    verify.addEventListener('click', () => { message.textContent = 'Verifico…'; send({ type: 'account-verify', server: 'mail' }); });
    box.append(login, verify, message);
    return box;
  }

  function render() {
    body.replaceChildren();
    fields.clear();
    const groups = wizard ? [GROUPS[step]] : GROUPS;
    for (const group of groups) {
      const section = node('section', 'settings-group');
      section.append(node('h3', null, group));
      settings.filter((s) => s.group === group).forEach((s) => section.append(field(s)));
      body.append(section);
    }
    title.textContent = wizard ? 'Configuriamo Jarvis' : 'Impostazioni';
    intro.textContent = wizard
      ? `Passo ${step + 1} di ${GROUPS.length}. Puoi cambiare tutto più tardi da Impostazioni.`
      : 'Le modifiche si applicano con un breve riavvio di Jarvis.';
    back.hidden = !wizard || step === 0;
    next.hidden = !wizard || step === GROUPS.length - 1;
    save.hidden = wizard && step < GROUPS.length - 1;
    later.hidden = !wizard;
    status.textContent = '';
  }

  /** The values of every step seen so far are kept while moving between steps. */
  const pending = new Map();
  const pendingClears = new Set();

  function collect() {
    for (const [name, f] of fields) {
      const value = f.read();
      if (value !== undefined) pending.set(name, value);
      if (f.clear?.()) pendingClears.add(name);
    }
  }

  back.addEventListener('click', () => { collect(); step -= 1; render(); });
  next.addEventListener('click', () => { collect(); step += 1; render(); });
  later.addEventListener('click', () => { sessionStorage.setItem('jarvis.setupLater', '1'); dialog.close(); });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    collect();
    status.textContent = 'Salvo…';
    send({ type: 'settings-save', values: Object.fromEntries(pending), cleared: [...pendingClears] });
  });

  return {
    open(asWizard) {
      wizard = asWizard;
      step = 0;
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
          status.textContent = 'Salvate. Riavvio Jarvis per applicarle…';
          return true;
        case 'settings-secret': {
          const input = body.querySelector(`[data-secret="${CSS.escape(msg.name)}"]`);
          if (input && !input.value) input.value = msg.value;
          return true;
        }
        case 'settings-error':
          status.textContent = msg.message;
          return true;
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
