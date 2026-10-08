// PokéRogue for the desktop (Linux and Windows): the online game in a window, a side
// panel like the phone app's (Tab), tools that open over the game, a key per tool,
// run history shared with the phone through my own store, and updates from my server.
// Grew out of Admiral-Billy's Pokerogue-App (MIT): the tool list and type charts come from there.
const { app, BaseWindow, WebContentsView, ipcMain, net, shell, dialog } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const keys = require('./keys');
const history = require('./history');
const updater = require('./updater');

const GAME_URL = process.env.PR_GAME_URL || 'https://pokerogue.net/';
const API_URL = process.env.PR_API_URL || 'https://api.pokerogue.net/';
const STORE_URL = process.env.PR_STORE_URL || 'https://fionnhughes.dev/pr/h/';
const BUILD = (() => {
  try { return JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'build.json'), 'utf8')).build || 0; } catch (e) { return 0; }
})();

const TOOLS = [
  { id: 'wiki', name: 'PokéRogue Wiki', url: 'https://wiki.pokerogue.net/' },
  { id: 'pokedex', name: 'Pokedex', url: 'https://ydarissep.github.io/PokeRogue-Pokedex' },
  { id: 'typecalc', name: 'Type Calculator', url: 'https://www.pkmn.help' },
  { id: 'teambuilder', name: 'Team Builder', url: 'https://marriland.com/tools/team-builder/' },
  { id: 'smogon', name: 'Smogon', url: 'https://www.smogon.com/dex/sv/pokemon/' },
  { id: 'typechart', name: 'Type Chart', file: path.join(__dirname, 'ui', 'typechart.html') }
];

const FIRST_SHARE_MS = 8000;
const SHARE_EVERY_MS = 180000; // look for runs from other devices
const WATCH_MS = 15000; // look whether the game has recorded a new run

// ---- settings ----

const settingsFile = () => path.join(app.getPath('userData'), 'desktop-settings.json');
const DEFAULTS = {
  panelKey: { code: 'Tab' }, toolKeys: {}, historyCode: '', muted: false, dark: false,
  size: [1280, 770], fullscreen: false, maximized: false
};
let settings = { ...DEFAULTS };

function loadSettings() {
  try { settings = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(settingsFile(), 'utf8')) }; } catch (e) { settings = { ...DEFAULTS }; }
  if (!settings.panelKey || !settings.panelKey.code) { settings.panelKey = DEFAULTS.panelKey; }
}
function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(settingsFile()), { recursive: true });
    fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 1));
  } catch (e) {
    console.error('settings not saved:', e);
  }
}

// ---- window and views ----

let win;
let game;
let overlay;
const toolViews = {}; // tool id -> WebContentsView, kept while the app runs
const typing = new Map(); // webContents id -> whether a text field has focus
let panelOpen = false;
let sheetTool = null; // id of the tool on screen
let capturing = false; // the panel is waiting for a key to bind
let darkCss = null;
const swallowed = new Set(); // keys whose key-down was taken, so their key-up is taken too

const state = {
  history: { on: false, line: 'Off. Set a code to share finished runs with your phone.' },
  update: { line: '' }
};

function webPrefs(preload) {
  return { preload: path.join(__dirname, preload), contextIsolation: true, sandbox: true, nodeIntegration: false };
}

function createWindow() {
  win = new BaseWindow({
    width: settings.size[0], height: settings.size[1], minWidth: 640, minHeight: 400,
    title: 'PokéRogue', backgroundColor: '#000000', show: false,
    icon: path.join(__dirname, '..', 'icons', process.platform === 'win32' ? 'PR.ico' : 'PR.png')
  });
  win.setMenu(null);

  game = new WebContentsView({ webPreferences: webPrefs('preload-watch.js') });
  game.setBackgroundColor('#000000');
  win.contentView.addChildView(game);
  watchKeys(game);
  game.webContents.setAudioMuted(!!settings.muted);
  game.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: 'deny' };
  });
  game.webContents.on('did-finish-load', () => {
    applyDark();
    scheduleSharing();
  });
  void game.webContents.loadURL(GAME_URL);

  overlay = new WebContentsView({ webPreferences: webPrefs('preload-ui.js') });
  overlay.setBackgroundColor('#00000000');
  watchKeys(overlay);
  void overlay.webContents.loadFile(path.join(__dirname, 'ui', 'panel.html'));

  // On Linux, maximizing and full screen do not always send 'resize', and the size
  // they report can lag behind. Lay out on every such event, again shortly after,
  // and whenever a quick check finds the size changed.
  const relayout = () => { layout(); setTimeout(layout, 60); setTimeout(layout, 300); };
  for (const name of ['resize', 'resized', 'maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen', 'restore']) {
    win.on(name, relayout);
  }
  const sizeCheck = setInterval(() => {
    if (!win || win.isDestroyed()) { clearInterval(sizeCheck); return; }
    const { width, height } = win.getContentBounds();
    if (width + 'x' + height !== laidOut) { layout(); }
  }, 250);
  win.on('close', () => {
    if (!win.isFullScreen() && !win.isMaximized()) { settings.size = win.getSize(); }
    settings.fullscreen = win.isFullScreen();
    settings.maximized = win.isMaximized();
    saveSettings();
  });
  win.on('closed', () => app.quit());

  layout();
  if (settings.maximized) { win.maximize(); }
  if (settings.fullscreen) { win.setFullScreen(true); }
  win.show();
  game.webContents.focus();
}

/** Where the tool sheet sits: the lower part of the window, like the phone's bottom sheet. */
function sheetBounds() {
  const { width, height } = win.getContentBounds();
  const w = Math.min(width, 1200);
  const top = Math.round(height * 0.1);
  return { x: Math.round((width - w) / 2), y: top, width: w, height: height - top, header: 64 };
}

let laidOut = '';

function layout() {
  if (!win || win.isDestroyed()) { return; }
  const { width, height } = win.getContentBounds();
  laidOut = width + 'x' + height;
  game.setBounds({ x: 0, y: 0, width, height });
  overlay.setBounds({ x: 0, y: 0, width, height });
  const sheet = sheetBounds();
  if (sheetTool) {
    toolViews[sheetTool].setBounds({ x: sheet.x, y: sheet.y + sheet.header, width: sheet.width, height: sheet.height - sheet.header });
  }
  pushState();
}

/** Puts the views in order: game, then the sheet's frame and tool, then the panel above all. */
function arrange() {
  const children = win.contentView.children;
  const show = (view) => win.contentView.addChildView(view); // adding a child again moves it to the top
  const hide = (view) => { if (children.includes(view)) { win.contentView.removeChildView(view); } };
  for (const [id, view] of Object.entries(toolViews)) { if (id !== sheetTool) { hide(view); } }
  if (sheetTool || panelOpen) { show(overlay); } else { hide(overlay); }
  if (sheetTool) { show(toolViews[sheetTool]); }
  if (panelOpen && sheetTool) { show(overlay); }
  layout();
  if (panelOpen) { overlay.webContents.focus(); }
  else if (sheetTool) { toolViews[sheetTool].webContents.focus(); }
  else { game.webContents.focus(); }
}

function setPanel(open) {
  panelOpen = open;
  if (!open) { capturing = false; }
  arrange();
}

function toolView(tool) {
  if (!toolViews[tool.id]) {
    const view = new WebContentsView({ webPreferences: webPrefs('preload-watch.js') });
    view.setBackgroundColor('#ffffff');
    watchKeys(view);
    // Links that would open a new window open in the sheet instead.
    view.webContents.setWindowOpenHandler(({ url }) => {
      if (/^https?:/.test(url)) { void view.webContents.loadURL(url); }
      return { action: 'deny' };
    });
    view.webContents.on('did-navigate', pushState);
    view.webContents.on('did-navigate-in-page', pushState);
    view.webContents.on('page-title-updated', pushState);
    toolViews[tool.id] = view;
    home(tool);
  }
  return toolViews[tool.id];
}

function home(tool) {
  const contents = toolViews[tool.id].webContents;
  if (tool.file) { void contents.loadFile(tool.file); } else { void contents.loadURL(tool.url); }
}

/** A tool's key or panel entry: shows the tool, or hides it if it is already showing. */
function toggleTool(id) {
  const tool = TOOLS.find((t) => t.id === id);
  if (!tool) { return; }
  if (sheetTool === id && !panelOpen) {
    sheetTool = null;
  } else {
    toolView(tool);
    sheetTool = id;
  }
  panelOpen = false;
  capturing = false;
  arrange();
}

function closeTop() {
  if (panelOpen) { setPanel(false); return; }
  if (sheetTool) { sheetTool = null; arrange(); }
}

// ---- keys ----

function isTyping(contents) { return typing.get(contents.id) === true; }

function watchKeys(view) {
  view.webContents.on('before-input-event', (event, input) => onKey(view, event, input));
}

function onKey(view, event, input) {
  const code = input.code || '';
  if (input.type === 'keyUp') {
    if (swallowed.delete(code)) { event.preventDefault(); }
    return;
  }
  if (input.type !== 'keyDown' || capturing) { return; }
  const contents = view.webContents;
  const take = () => { event.preventDefault(); swallowed.add(code); };
  const plain = !input.control && !input.alt && !input.meta && !input.shift;

  // Keys this app keeps, wherever the focus is.
  if (plain && code === 'F11') { take(); win.setFullScreen(!win.isFullScreen()); pushState(); return; }
  if (plain && code === 'F12') { take(); contents.toggleDevTools(); return; }
  if (view === game && (code === 'F5' && plain || code === 'KeyR' && input.control && !input.shift)) {
    take(); game.webContents.reload(); return;
  }
  if (isTyping(contents)) { return; }

  const binding = keys.fromInput(input);
  if (input.isAutoRepeat) {
    if (keys.same(binding, settings.panelKey) || Object.values(settings.toolKeys).some((b) => keys.same(b, binding))) { take(); }
    return;
  }
  if (keys.same(binding, settings.panelKey)) { take(); setPanel(!panelOpen); return; }
  for (const [id, bound] of Object.entries(settings.toolKeys)) {
    if (keys.same(binding, bound)) { take(); toggleTool(id); return; }
  }
  if (code === 'Escape' && plain && view !== game && (panelOpen || sheetTool)) { take(); closeTop(); }
}

/** The keys the game uses right now, from its stored layout. */
async function gameKeys() {
  try {
    return keys.gameKeys(await game.webContents.executeJavaScript("localStorage.getItem('mappingConfigs')"));
  } catch (e) {
    return keys.gameKeys(null);
  }
}

/** Binds a key to the panel ('panel') or a tool. null removes a tool's key. Resolves to '' or why not. */
async function bind(target, binding) {
  const others = [{ name: 'the side panel', id: 'panel', binding: settings.panelKey }]
    .concat(TOOLS.map((t) => ({ name: t.name, id: t.id, binding: settings.toolKeys[t.id] })))
    .filter((o) => o.id !== target && o.binding);
  if (binding === null) {
    if (target === 'panel') { return 'The side panel needs a key.'; }
    delete settings.toolKeys[target];
  } else {
    const why = keys.refuse(binding, await gameKeys(), others);
    if (why) { return why; }
    if (target === 'panel') { settings.panelKey = binding; } else { settings.toolKeys[target] = binding; }
  }
  saveSettings();
  pushState();
  return '';
}

// ---- quick toggles ----

async function applyDark() {
  if (darkCss) {
    await game.webContents.removeInsertedCSS(darkCss).catch(() => {});
    darkCss = null;
  }
  if (settings.dark) { darkCss = await game.webContents.insertCSS('#app { background: black !important; }').catch(() => null); }
}

function toggle(name) {
  if (name === 'fullscreen') { win.setFullScreen(!win.isFullScreen()); }
  if (name === 'muted') { settings.muted = !settings.muted; game.webContents.setAudioMuted(settings.muted); }
  if (name === 'dark') { settings.dark = !settings.dark; void applyDark(); }
  saveSettings();
  setTimeout(pushState, 50);
}

// ---- run history sharing ----

let sharing = false;
let lastStored = null;
let shareTimer = null;
let watchTimer = null;

async function readStored(user) {
  return (await game.webContents.executeJavaScript(`localStorage.getItem(${JSON.stringify('runHistoryData_' + user)}) || ''`)) || '';
}

/** The logged-in account, asked of the game's server with the game's login cookie. */
/**
 * The logged-in account. Asked from inside the game's page, the way the game asks:
 * its login cookie as the Authorization header. The game's server turns away the
 * same request made from outside a page. If it cannot say, a single account with
 * stored run history in this game is taken to be the one.
 */
async function accountName() {
  return game.webContents.executeJavaScript(`(async () => {
    const tokens = [...new Set(document.cookie.split(';').map((c) => c.trim())
      .filter((c) => c.startsWith('pokerogue_sessionId=')).map((c) => c.slice(20)).filter(Boolean))];
    for (const token of tokens) {
      try {
        const response = await fetch(${JSON.stringify(API_URL)} + 'account/info', { headers: { Authorization: token } });
        if (response.ok) {
          const info = await response.json();
          if (info && info.username) { return info.username; }
        }
      } catch (e) {
        // try the next one
      }
    }
    const names = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key.startsWith('runHistoryData_') && key !== 'runHistoryData_Guest') { names.push(key.slice(15)); }
    }
    return names.length === 1 ? names[0] : '';
  })()`);
}

function storeRequest(method, url, body) {
  const target = new URL(url);
  if (target.protocol !== 'https:' && !['127.0.0.1', 'localhost'].includes(target.hostname)) {
    return Promise.reject(new Error('the store must use https'));
  }
  return net.fetch(url, {
    method, body,
    headers: { Authorization: 'Bearer ' + settings.historyCode, 'Content-Type': 'application/json' }
  }).then(async (response) => ({ status: response.status, text: await response.text() }));
}

async function shareHistory() {
  if (!settings.historyCode) {
    state.history = { on: false, line: 'Off. Set a code to share finished runs with your phone.' };
    pushState();
    return state.history.line;
  }
  if (sharing) { return 'already running'; }
  sharing = true;
  const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  let line;
  try {
    const user = await accountName();
    if (!user) { throw new Error('not logged in'); }
    const store = STORE_URL + encodeURIComponent(user) + '/';
    const result = await history.exchange(store, {
      request: storeRequest,
      readStored: () => readStored(user),
      writeStored: (cipher) => game.webContents.executeJavaScript(
        `localStorage.setItem(${JSON.stringify('runHistoryData_' + user)}, ${JSON.stringify(cipher)})`)
    });
    lastStored = { user, text: await readStored(user) };
    line = `${time}: ${result.received} received, ${result.sent} sent (${user}: ${result.inBrowser} runs here, ${result.inStore} in the store before)`;
  } catch (e) {
    line = `${time}: failed, ${(e && e.message) || e}`;
  }
  sharing = false;
  state.history = { on: true, line };
  console.log('[run history] ' + line);
  pushState();
  return line;
}

function scheduleSharing() {
  if (!new URL(game.webContents.getURL() || GAME_URL).href.startsWith(new URL(GAME_URL).origin)) { return; }
  clearTimeout(shareTimer);
  clearInterval(watchTimer);
  shareTimer = setTimeout(function again() {
    void shareHistory();
    shareTimer = setTimeout(again, SHARE_EVERY_MS);
  }, FIRST_SHARE_MS);
  // The game records a finished run by rewriting its stored history: the moment to send it.
  watchTimer = setInterval(async () => {
    if (sharing || !settings.historyCode || !lastStored) { return; }
    try {
      if ((await readStored(lastStored.user)) !== lastStored.text) { void shareHistory(); }
    } catch (e) {
      // the page is reloading
    }
  }, WATCH_MS);
}

// ---- updates ----

let updating = false;

async function checkForUpdates(manual) {
  if (updating) { return; }
  let latest;
  try {
    latest = await updater.check(net.fetch);
  } catch (e) {
    state.update = { line: 'Update check failed: ' + e.message };
    pushState();
    if (manual) { void dialog.showMessageBox(win, { type: 'warning', message: 'Could not check for updates.', detail: e.message }); }
    return;
  }
  if (latest.build <= BUILD) {
    state.update = { line: 'Up to date (build ' + BUILD + ').' };
    pushState();
    if (manual) { void dialog.showMessageBox(win, { type: 'info', message: 'You have the newest build (' + BUILD + ').' }); }
    return;
  }
  state.update = { line: 'Build ' + latest.build + ' is available.' };
  pushState();
  const file = updater.pick(latest, process.platform, process.env.APPIMAGE);
  const answer = await dialog.showMessageBox(win, {
    type: 'info',
    message: 'An update is available (build ' + latest.build + ', you have ' + BUILD + ').',
    detail: (latest.notes || '') + (file ? '' : '\n\nThis copy cannot update itself. Download the new build from the update page.'),
    buttons: file ? ['Update now', 'Later'] : ['Open the update page', 'Later'],
    defaultId: 0, cancelId: 1
  });
  if (answer.response !== 0) { return; }
  if (!file) { void shell.openExternal(updater.fileUrl('./')); return; }
  await installUpdate(file);
}

async function installUpdate(file) {
  updating = true;
  try {
    if (process.platform === 'win32') {
      const dest = path.join(os.tmpdir(), 'PokeRogue-Setup-' + Date.now() + '.exe');
      await updater.download(net.fetch, file, dest, (f) => win.setProgressBar(f));
      // Installs quietly over this copy and starts the new one.
      spawn(dest, ['/S', '--force-run'], { detached: true, stdio: 'ignore' }).unref();
      app.quit();
      return;
    }
    const target = process.env.APPIMAGE;
    const dest = target + '.new';
    await updater.download(net.fetch, file, dest, (f) => win.setProgressBar(f));
    fs.chmodSync(dest, 0o755);
    fs.renameSync(dest, target);
    app.relaunch({ execPath: target, args: process.argv.slice(1) });
    app.exit(0);
  } catch (e) {
    updating = false;
    win.setProgressBar(-1);
    const answer = await dialog.showMessageBox(win, {
      type: 'error', message: 'The update failed.', detail: e.message,
      buttons: ['Open the update page', 'Close'], defaultId: 1, cancelId: 1
    });
    if (answer.response === 0) { void shell.openExternal(updater.fileUrl('./')); }
  }
}

// ---- the panel page ----

function snapshot() {
  const current = sheetTool ? toolViews[sheetTool]?.webContents : null;
  return {
    panelOpen, sheetTool, capturing, build: BUILD,
    sheet: win ? sheetBounds() : null,
    sheetTitle: current ? (current.getTitle() || TOOLS.find((t) => t.id === sheetTool).name) : '',
    canBack: current ? current.navigationHistory.canGoBack() : false,
    canForward: current ? current.navigationHistory.canGoForward() : false,
    panelKey: keys.label(settings.panelKey),
    tools: TOOLS.map((t) => ({ id: t.id, name: t.name, key: keys.label(settings.toolKeys[t.id]) })),
    quick: { fullscreen: !!(win && win.isFullScreen()), muted: !!settings.muted, dark: !!settings.dark },
    hasCode: !!settings.historyCode,
    history: state.history, update: state.update
  };
}

function pushState() {
  if (overlay && !overlay.webContents.isDestroyed()) { overlay.webContents.send('state', snapshot()); }
}

ipcMain.on('typing', (event, on) => typing.set(event.sender.id, !!on));
ipcMain.handle('ui:state', () => snapshot());
ipcMain.handle('ui:close', () => closeTop());
ipcMain.handle('ui:closePanel', () => setPanel(false));
ipcMain.handle('ui:closeSheet', () => { sheetTool = null; panelOpen = false; arrange(); });
ipcMain.handle('ui:tool', (event, id) => toggleTool(id));
ipcMain.handle('ui:toggle', (event, name) => toggle(name));
ipcMain.handle('ui:capturing', (event, on) => { capturing = !!on; pushState(); });
ipcMain.handle('ui:bind', (event, target, binding) => bind(target, binding));
ipcMain.handle('ui:setCode', (event, code) => {
  const typed = String(code || '').trim();
  if (typed && typed.length < 12) { return 'The code has at least 12 characters.'; }
  settings.historyCode = typed;
  saveSettings();
  void shareHistory();
  return '';
});
ipcMain.handle('ui:shareNow', () => shareHistory());
ipcMain.handle('ui:checkUpdates', () => { setPanel(false); return checkForUpdates(true); });
ipcMain.handle('ui:reload', () => { setPanel(false); game.webContents.reload(); });
ipcMain.handle('ui:nav', (event, action) => {
  const tool = TOOLS.find((t) => t.id === sheetTool);
  if (!tool) { return; }
  const contents = toolViews[tool.id].webContents;
  if (action === 'back' && contents.navigationHistory.canGoBack()) { contents.navigationHistory.goBack(); }
  if (action === 'forward' && contents.navigationHistory.canGoForward()) { contents.navigationHistory.goForward(); }
  if (action === 'home') { home(tool); }
  if (action === 'browser' && tool.url) { void shell.openExternal(contents.getURL()); }
});

// ---- start ----

if (process.env.PR_USER_DATA) { app.setPath('userData', process.env.PR_USER_DATA); } // for tests

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!win) { return; }
    if (win.isMinimized()) { win.restore(); }
    win.focus();
  });
  app.whenReady().then(() => {
    loadSettings();
    createWindow();
    if (BUILD > 0) { setTimeout(() => void checkForUpdates(false), 10000); } // a copy built by hand has no build number
  });
  app.on('window-all-closed', () => app.quit());
}
