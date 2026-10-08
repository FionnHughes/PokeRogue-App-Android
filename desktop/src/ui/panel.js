// The side panel and the tool sheet's frame. The app sends its state; this page draws it
// and sends back what was clicked.
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const EDIT_ICON = '<svg viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>';
  const MODIFIERS = ['ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight', 'AltLeft', 'AltRight', 'MetaLeft', 'MetaRight'];

  let state = null;
  let capture = null; // the panel or tool id waiting for a key
  let captureMessage = '';
  let editingCode = false;
  let chooserOpen = false;
  let chooserLoaded = false;
  let shownChoice = false;

  function text(el, value) { if (el.textContent !== value) { el.textContent = value; } }

  function buildTools() {
    const box = $('tools');
    box.textContent = '';
    for (const tool of state.tools) {
      const row = document.createElement('div');
      row.className = 'item keyed';
      row.dataset.target = tool.id;
      row.innerHTML = '<button class="main"><span></span></button><span class="chip" data-chip></span>'
        + '<button class="icon small" data-edit title="Set a key">' + EDIT_ICON + '</button>';
      row.querySelector('.main span').textContent = tool.name;
      row.querySelector('.main').addEventListener('click', () => window.app.tool(tool.id));
      box.appendChild(row);
      const hint = document.createElement('div');
      hint.className = 'capture-hint';
      hint.dataset.hintFor = tool.id;
      hint.hidden = true;
      box.appendChild(hint);
    }
    wireEdits();
  }

  function wireEdits() {
    for (const button of document.querySelectorAll('[data-edit]')) {
      if (button.dataset.wired) { continue; }
      button.dataset.wired = '1';
      button.addEventListener('click', () => startCapture(button.closest('.keyed').dataset.target));
    }
  }

  function render() {
    if (!state) { return; }
    const panel = state.panelOpen;
    const sheet = !!state.sheetTool;
    document.body.classList.toggle('dim', panel || sheet);
    $('drawer').hidden = !panel;
    $('sheet').hidden = !sheet;

    if (sheet && state.sheet) {
      const s = $('sheet').style;
      s.left = state.sheet.x + 'px';
      s.top = state.sheet.y + 'px';
      s.width = state.sheet.width + 'px';
      s.height = state.sheet.height + 'px';
      text($('sheet-title'), state.sheetTitle);
      $('nav-back').disabled = !state.canBack;
      $('nav-forward').disabled = !state.canForward;
      $('nav-browser').hidden = state.sheetTool === 'typechart';
    }

    text($('build'), 'Desktop' + (state.build ? ', build ' + state.build : ', hand-built copy'));
    if (!$('tools').children.length || $('tools').querySelectorAll('.keyed').length !== state.tools.length) { buildTools(); }
    for (const row of document.querySelectorAll('.keyed')) {
      const target = row.dataset.target;
      const key = target === 'panel' ? state.panelKey : (state.tools.find((t) => t.id === target) || {}).key;
      const chip = row.querySelector('[data-chip]');
      const waiting = capture === target;
      text(chip, waiting ? 'Press a key' : (key || 'No key'));
      chip.classList.toggle('empty', !key && !waiting);
      row.classList.toggle('capturing', waiting);
      row.classList.toggle('open', target === state.sheetTool);
      const hint = document.querySelector('[data-hint-for="' + target + '"]');
      if (hint) {
        hint.hidden = !waiting;
        if (waiting) {
          hint.innerHTML = '';
          const line = document.createElement('div');
          line.textContent = 'Esc cancels.' + (target === 'panel' ? '' : ' Backspace removes the key.')
            + ' Ctrl or Alt with a key works too.';
          hint.appendChild(line);
          if (captureMessage) {
            const error = document.createElement('div');
            error.className = 'error';
            error.textContent = captureMessage;
            hint.appendChild(error);
          }
        }
      }
    }

    for (const button of document.querySelectorAll('[data-toggle]')) {
      button.classList.toggle('on', !!state.quick[button.dataset.toggle]);
      button.setAttribute('aria-pressed', String(!!state.quick[button.dataset.toggle]));
    }

    text($('history-line'), state.history.line);
    $('code-edit').hidden = !editingCode;
    $('share-now').hidden = editingCode || !state.hasCode;
    $('set-code').hidden = editingCode;
    $('save-code').hidden = !editingCode;
    $('cancel-code').hidden = !editingCode;
    text($('set-code'), state.hasCode ? 'Change code' : 'Set code');
    text($('update-line'), state.update.line || '');

    const share = state.settingsShare || {};
    text($('settings-line'), state.hasCode ? (share.line || 'Waiting for the first exchange.') : 'Set a code under Run history sharing first.');
    const showChooser = state.hasCode && (chooserOpen || !!share.choose);
    $('chooser').hidden = !showChooser;
    $('show-chooser').hidden = !state.hasCode || showChooser;
    $('hide-chooser').hidden = !showChooser || !!share.choose;
    $('apply-settings').hidden = !share.pending;
    if (showChooser && !chooserLoaded) { void loadCandidates(); }
    // Opened by itself to ask for the choice: show that part.
    if (share.choose && state.panelOpen && !shownChoice) {
      shownChoice = true;
      $('settings-card').scrollIntoView({ block: 'center' });
    }
    text($('backup-line'), (state.backup && state.backup.line) || '');
  }

  // ---- shared settings ----

  function when(ms) {
    const d = new Date(ms);
    return d.toLocaleDateString([], { day: 'numeric', month: 'short' }) + ', ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  async function loadCandidates() {
    chooserLoaded = true;
    const box = $('candidates');
    box.textContent = 'Loading...';
    const answer = await window.app.candidates();
    box.textContent = '';
    if (answer.error) { text($('choose-error'), answer.error); return; }
    text($('choose-error'), '');
    if (!answer.list.length) { box.textContent = 'No device has shared its settings yet.'; return; }
    for (const c of answer.list) {
      const button = document.createElement('button');
      button.className = 'candidate';
      const name = document.createElement('span');
      name.className = 'name';
      name.textContent = 'Use: ' + c.from + (c.mine ? ' (this computer)' : '');
      const at = document.createElement('span');
      at.className = 'when';
      at.textContent = 'Shared ' + when(c.updated);
      button.append(name, at);
      button.addEventListener('click', async () => {
        text($('choose-error'), '');
        const why = await window.app.choose(c.device);
        if (why) { text($('choose-error'), why); return; }
        chooserOpen = false;
        chooserLoaded = false;
        render();
      });
      box.appendChild(button);
    }
  }

  // ---- keys ----

  function startCapture(target) {
    capture = target;
    captureMessage = '';
    void window.app.capturing(true);
    render();
  }
  function endCapture() {
    capture = null;
    captureMessage = '';
    void window.app.capturing(false);
    render();
  }

  document.addEventListener('keydown', async (e) => {
    if (capture) {
      e.preventDefault();
      if (MODIFIERS.includes(e.code)) { return; } // wait for the key held with it
      if (e.code === 'Escape') { endCapture(); return; }
      const binding = (e.code === 'Backspace' || e.code === 'Delete') && !e.ctrlKey && !e.altKey
        ? null
        : { code: e.code, ctrl: e.ctrlKey || e.metaKey, alt: e.altKey, shift: e.shiftKey };
      const target = capture;
      const why = await window.app.bind(target, binding);
      if (capture !== target) { return; }
      if (why) { captureMessage = why; render(); } else { endCapture(); }
      return;
    }
    if (editingCode && e.code === 'Enter' && document.activeElement === $('code')) { e.preventDefault(); void saveCode(); return; }
    if (editingCode && e.code === 'Escape') { e.preventDefault(); stopEditingCode(); return; }
    // Arrow keys move through the panel's buttons.
    if ((e.code === 'ArrowDown' || e.code === 'ArrowUp') && state && state.panelOpen && document.activeElement !== $('code')) {
      e.preventDefault();
      const all = [...$('drawer').querySelectorAll('button')].filter((b) => b.offsetParent && !b.disabled);
      const at = all.indexOf(document.activeElement);
      const next = e.code === 'ArrowDown' ? (at + 1) % all.length : (at <= 0 ? all.length - 1 : at - 1);
      all[next].focus();
    }
  });

  // ---- run history code ----

  function stopEditingCode() {
    editingCode = false;
    window.app.typing(false);
    render();
  }
  async function saveCode() {
    const why = await window.app.setCode($('code').value);
    if (why) { text($('code-error'), why); return; }
    stopEditingCode();
  }
  $('set-code').addEventListener('click', () => {
    editingCode = true;
    $('code').value = '';
    text($('code-error'), '');
    render();
    $('code').focus();
  });
  $('save-code').addEventListener('click', () => void saveCode());
  $('cancel-code').addEventListener('click', stopEditingCode);
  $('code').addEventListener('focus', () => window.app.typing(true));
  $('code').addEventListener('blur', () => window.app.typing(false));
  $('share-now').addEventListener('click', () => void window.app.shareNow());
  $('show-chooser').addEventListener('click', () => { chooserOpen = true; chooserLoaded = false; render(); });
  $('hide-chooser').addEventListener('click', () => { chooserOpen = false; render(); });
  $('apply-settings').addEventListener('click', () => void window.app.applySettings());

  // ---- everything else ----

  for (const button of document.querySelectorAll('[data-toggle]')) {
    button.addEventListener('click', () => void window.app.toggle(button.dataset.toggle));
  }
  $('panel-key-row').addEventListener('click', () => startCapture('panel'));
  $('reload').addEventListener('click', () => void window.app.reload());
  $('updates').addEventListener('click', () => void window.app.checkUpdates());
  $('credits-button').addEventListener('click', () => { $('credits').hidden = !$('credits').hidden; });
  $('scrim').addEventListener('click', () => {
    if (capture) { endCapture(); }
    if (state && state.panelOpen) { void window.app.closePanel(); } else { void window.app.closeSheet(); }
  });
  $('nav-back').addEventListener('click', () => void window.app.nav('back'));
  $('nav-forward').addEventListener('click', () => void window.app.nav('forward'));
  $('nav-home').addEventListener('click', () => void window.app.nav('home'));
  $('nav-browser').addEventListener('click', () => void window.app.nav('browser'));
  $('nav-close').addEventListener('click', () => void window.app.closeSheet());
  wireEdits();

  window.app.onState((next) => {
    const closed = state && state.panelOpen && !next.panelOpen;
    state = next;
    if (closed) {
      if (capture) { capture = null; captureMessage = ''; }
      if (editingCode) { editingCode = false; window.app.typing(false); }
      $('credits').hidden = true;
      chooserOpen = false;
      chooserLoaded = false;
    }
    render();
  });
  window.app.state().then((first) => { state = first; render(); });
})();
