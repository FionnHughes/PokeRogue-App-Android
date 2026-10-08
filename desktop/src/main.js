// PokéRogue for the desktop (Linux and Windows): the online game in a window, a side
// panel like the phone app's (Tab), tools that open over the game, a key per tool,
// run history shared with the phone through my own store, and updates from my server.
// Grew out of Admiral-Billy's Pokerogue-App (MIT): the tool list and type charts come from there.
const { app, BaseWindow, WebContentsView, ipcMain, net, shell, dialog, clipboard } = require('electron');
const vm = require('vm');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const keys = require('./keys');
const history = require('./history');
const updater = require('./updater');
const settingsSync = require('./settings-sync');

const GAME_URL = process.env.PR_GAME_URL || 'https://pokerogue.net/';
const API_URL = process.env.PR_API_URL || 'https://api.pokerogue.net/';
const STORE_URL = process.env.PR_STORE_URL || 'https://fionnhughes.dev/pr/h/';
const BUILD = (() => {
  try { return JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'build.json'), 'utf8')).build || 0; } catch (e) { return 0; }
})();

const TOOLS = [
  { id: 'wiki', name: 'PokéRogue Wiki', url: 'https://wiki.pokerogue.net/' },
  // The Pokedex the phone app shipped with (ydarissep.github.io) is gone; Sandstorm's SearchDex replaces it.
  { id: 'pokedex', name: 'Pokedex', url: 'https://sandstormer.github.io/PokeRogue-Dex/' },
  { id: 'typecalc', name: 'Type Calculator', url: 'https://www.pkmn.help' },
  { id: 'teambuilder', name: 'Team Builder', url: 'https://marriland.com/tools/team-builder/' },
  { id: 'smogon', name: 'Smogon', url: 'https://www.smogon.com/dex/sv/pokemon/' },
  { id: 'typechart', name: 'Type Chart', file: path.join(__dirname, 'ui', 'typechart.html') }
];

/** The built-in tools and the player's own pages, as one list. */
function allTools() {
  return TOOLS.concat(myPages().map((p) => ({ id: pageId(p.url), name: p.name, url: p.url, mine: true })));
}
function myPages() { return (settings.pages && Array.isArray(settings.pages.pages)) ? settings.pages.pages : []; }
function pageId(url) { return 'page-' + require('crypto').createHash('sha1').update(url).digest('hex').slice(0, 10); }

const FIRST_SHARE_MS = 8000;
const SHARE_EVERY_MS = 180000; // look for runs from other devices
const WATCH_MS = 15000; // look whether the game has recorded a new run or changed its settings
const BACKUP_GAP_MS = 10 * 60000; // at most one backup to the store this often
// This computer, as the store's settings and backups name it.
const DEVICE = 'pc-' + (os.hostname().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30) || 'computer');
const DEVICE_LABEL = (process.platform === 'win32' ? 'Windows PC' : 'Linux PC') + ' (' + os.hostname() + ')';

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
  settings: { line: '', choose: false, pending: false },
  pages: { line: '' },
  extras: { line: '', teams: [] },
  backup: { line: '' },
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
  const tool = allTools().find((t) => t.id === id);
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
    .concat(allTools().map((t) => ({ name: t.name, id: t.id, binding: settings.toolKeys[t.id] })))
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
    currentUser = user;
    const store = storeFor(user);
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

let currentUser = '';

function storeFor(user) { return STORE_URL + encodeURIComponent(user) + '/'; }
function clock() { return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }

// ---- shared settings ----

let settingsBusy = false;
let settingsSeen = null; // the game's settings as last looked at, to notice a change
let settingsChangedAt = 0; // when this computer's change was noticed
let pendingSettings = null; // shared settings changed elsewhere, waiting for the next start
let offeredChoice = false;

async function readSettings() {
  return game.webContents.executeJavaScript(`(() => {
    const out = {};
    for (const key of ${JSON.stringify(settingsSync.KEYS)}) { out[key] = localStorage.getItem(key); }
    return out;
  })()`);
}

async function writeSettings(values) {
  await game.webContents.executeJavaScript(`(() => {
    const values = ${JSON.stringify(values)};
    for (const key of Object.keys(values)) { localStorage.setItem(key, values[key]); }
  })()`);
}

function rememberSettings(user, last) {
  settings.sharedSettings = { ...(settings.sharedSettings || {}), [user.toLowerCase()]: last };
  saveSettings();
}

/**
 * Exchanges settings with the store. reason: 'start' (the game just loaded: shared
 * settings are written and the game reloads to use them), 'watch' (this computer
 * changed them), 'periodic', 'apply' (the player asked) or 'chosen'.
 */
async function shareSettings(reason) {
  if (!settings.historyCode || settingsBusy) { return; }
  settingsBusy = true;
  try {
    const user = currentUser || await accountName();
    if (!user) { throw new Error('not logged in'); }
    currentUser = user;
    const local = await readSettings();
    const result = await settingsSync.exchange({
      request: storeRequest, store: storeFor(user), device: DEVICE, label: DEVICE_LABEL, local,
      last: (settings.sharedSettings || {})[user.toLowerCase()] || null, changedAt: settingsChangedAt, now: Date.now()
    });
    settingsSeen = settingsSync.hash(local);
    if (result.action === 'choose') {
      state.settings = { line: 'Not chosen yet. Pick whose settings every device should use.', choose: true, pending: false };
      if (reason === 'start' && !offeredChoice) {
        offeredChoice = true;
        setPanel(true);
      }
    } else if (result.action === 'apply' && !['start', 'apply', 'chosen'].includes(reason)) {
      pendingSettings = result;
      state.settings = { line: `${result.shared.from} changed the shared settings. They apply the next time the game starts.`, choose: false, pending: true };
    } else if (result.action === 'apply') {
      await writeSettings(result.write);
      rememberSettings(user, result.last);
      settingsSeen = settingsSync.hash({ ...local, ...result.write });
      settingsChangedAt = 0;
      pendingSettings = null;
      state.settings = { line: `${clock()}: now using the shared settings (from ${result.shared.from}).`, choose: false, pending: false };
      game.webContents.reload(); // the game reads its settings only when it loads
    } else {
      rememberSettings(user, result.last);
      settingsChangedAt = 0;
      pendingSettings = null;
      state.settings = {
        line: result.action === 'pushed' ? `${clock()}: sent this computer's settings change.` : `Shared settings in use (from ${result.shared.from}).`,
        choose: false, pending: false
      };
    }
  } catch (e) {
    state.settings = { ...state.settings, line: `${clock()}: failed, ${(e && e.message) || e}` };
  }
  settingsBusy = false;
  pushState();
}

// ---- backups to the store ----

let lastBackup = { hash: null, at: 0 };

/** Sends the store a copy of the game's save data, runs in progress and run history, when they changed. */
async function backupToStore(force) {
  if (!settings.historyCode || !currentUser) { return; }
  if (!force && Date.now() - lastBackup.at < BACKUP_GAP_MS) { return; }
  const user = currentUser;
  try {
    const stored = await game.webContents.executeJavaScript(`(() => {
      const user = ${JSON.stringify(user)};
      const sessions = [];
      for (let slot = 0; slot < 5; slot++) { sessions.push(localStorage.getItem('sessionData' + (slot || '') + '_' + user) || ''); }
      return { save: localStorage.getItem('data_' + user) || '', history: localStorage.getItem('runHistoryData_' + user) || '', sessions };
    })()`);
    const payload = {
      from: DEVICE_LABEL, origin: 'online', account: user,
      save: history.decryptText(stored.save), history: history.decryptText(stored.history),
      sessions: stored.sessions.map((s) => history.decryptText(s))
    };
    if (!payload.save) { return; } // nothing saved yet in this game
    const hash = require('crypto').createHash('sha256').update(JSON.stringify(payload)).digest('hex');
    if (hash === lastBackup.hash) { return; }
    const made = Date.now();
    const answer = await storeRequest('PUT', storeFor(user) + 'backups/' + made + '-' + DEVICE, JSON.stringify({ made, ...payload }));
    if (answer.status < 200 || answer.status >= 300) { throw new Error('the store answered ' + answer.status); }
    lastBackup = { hash, at: made };
    state.backup = { line: `${clock()}: save data backed up to your server.` };
  } catch (e) {
    state.backup = { line: `${clock()}: backup failed, ${(e && e.message) || e}` };
  }
  pushState();
}

// ---- my pages ----

let pagesBusy = false;

/** Brings the page list up to date with the store: the list changed last wins. */
async function sharePages() {
  if (!settings.historyCode || !currentUser || pagesBusy) { return; }
  pagesBusy = true;
  try {
    const store = storeFor(currentUser);
    const local = settings.pages || { updated: 0, pages: [] };
    const got = await storeRequest('GET', store + 'pages');
    const remote = got.status === 200 ? JSON.parse(got.text) : null;
    if (got.status !== 200 && got.status !== 404) { throw new Error('the store answered ' + got.status); }
    if (remote && remote.updated > (local.updated || 0)) {
      settings.pages = remote;
      saveSettings();
    } else if ((local.updated || 0) > 0 && (!remote || local.updated > remote.updated)) {
      const sent = await storeRequest('PUT', store + 'pages', JSON.stringify(local));
      if (sent.status === 409) { settings.pages = JSON.parse(sent.text); saveSettings(); }
      else if (sent.status < 200 || sent.status >= 300) { throw new Error('the store answered ' + sent.status); }
    }
    state.pages = { line: '' };
  } catch (e) {
    state.pages = { line: `${clock()}: sharing the list failed, ${(e && e.message) || e}` };
  }
  pagesBusy = false;
  pushState();
}

/** Adds or changes (index given) a page, or removes one (entry null). Resolves to '' or why not. */
async function changePage(index, entry) {
  if (entry) {
    if (!/^https?:\/\/[^\s]+\.[^\s]+$/i.test(entry.url || '')) { return 'That is not a web address (https://...).'; }
    entry = { name: String(entry.name || '').trim() || new URL(entry.url).host, url: entry.url.trim() };
  }
  const pages = myPages().slice();
  if (index === null || index === undefined || index < 0) { pages.push(entry); }
  else if (entry) { pages[index] = entry; }
  else {
    const gone = pages.splice(index, 1)[0];
    if (gone) {
      delete settings.toolKeys[pageId(gone.url)];
      if (sheetTool === pageId(gone.url)) { sheetTool = null; arrange(); }
    }
  }
  settings.pages = { updated: Date.now(), pages };
  saveSettings();
  pushState();
  void sharePages();
  return '';
}

// ---- extras: copying to the clipboard ----

let gameTools = null;

/** The phone app's own scripts (tables.js, starters.js, team.js), run here. */
function vendorTools() {
  if (!gameTools) {
    const sandbox = { window: {}, console, atob: (b) => Buffer.from(b, 'base64').toString('latin1') };
    vm.createContext(sandbox);
    for (const name of ['tables.js', 'starters.js', 'team.js']) {
      vm.runInContext(fs.readFileSync(path.join(__dirname, 'vendor', name), 'utf8'), sandbox, { filename: name });
    }
    gameTools = sandbox.window;
  }
  return gameTools;
}

async function storedText(key) {
  return history.decryptText(await game.webContents.executeJavaScript(`localStorage.getItem(${JSON.stringify(key)}) || ''`));
}

async function copyCaught() {
  try {
    const user = currentUser || await accountName();
    if (!user) { throw new Error('log in to the game first'); }
    currentUser = user;
    const save = await storedText('data_' + user);
    if (!save) { throw new Error('the game has not stored its save data yet'); }
    const tools = vendorTools();
    clipboard.writeText(tools.__starterTools.format(save, 'online save'));
    state.extras = { line: `${clock()}: copied ${tools.__starterTools.count(save)} Pokémon to the clipboard.`, teams: [] };
  } catch (e) {
    state.extras = { line: `Could not copy: ${(e && e.message) || e}`, teams: [] };
  }
  pushState();
}

/** Copies a run's team; with several runs and no slot given, offers them instead. */
async function copyTeam(slot) {
  try {
    const user = currentUser || await accountName();
    if (!user) { throw new Error('log in to the game first'); }
    currentUser = user;
    const tools = vendorTools();
    const runs = [];
    for (let s = 0; s < 5; s++) {
      const text = await storedText('sessionData' + (s || '') + '_' + user);
      if (text) { runs.push({ slot: s, text }); }
    }
    if (!runs.length) { throw new Error('no run in progress in this game'); }
    const pick = slot === undefined || slot === null ? (runs.length === 1 ? runs[0] : null) : runs.find((r) => r.slot === slot);
    if (!pick) {
      state.extras = {
        line: 'Which run? Pick one below.',
        teams: runs.map((r) => ({ slot: r.slot, label: 'Slot ' + (r.slot + 1) + ': ' + tools.__teamTools.summary(r.text) }))
      };
    } else {
      clipboard.writeText(tools.__teamTools.format(pick.text, 'slot ' + (pick.slot + 1)));
      state.extras = { line: `${clock()}: copied the team in slot ${pick.slot + 1} to the clipboard.`, teams: [] };
    }
  } catch (e) {
    state.extras = { line: `Could not copy: ${(e && e.message) || e}`, teams: [] };
  }
  pushState();
}

async function shareAll(reason) {
  await shareHistory();
  await sharePages();
  await shareSettings(reason);
  await backupToStore(reason === 'start');
}

function scheduleSharing() {
  if (!new URL(game.webContents.getURL() || GAME_URL).href.startsWith(new URL(GAME_URL).origin)) { return; }
  clearTimeout(shareTimer);
  clearInterval(watchTimer);
  shareTimer = setTimeout(() => {
    void shareAll('start');
    shareTimer = setTimeout(function again() {
      void shareAll('periodic');
      shareTimer = setTimeout(again, SHARE_EVERY_MS);
    }, SHARE_EVERY_MS);
  }, FIRST_SHARE_MS);
  watchTimer = setInterval(async () => {
    if (!settings.historyCode) { return; }
    try {
      // The game records a finished run by rewriting its stored history: the moment to send it.
      if (!sharing && lastStored && (await readStored(lastStored.user)) !== lastStored.text) { void shareHistory(); }
      // A setting changed here: send it.
      if (!settingsBusy && settingsSeen && settingsSync.hash(await readSettings()) !== settingsSeen) {
        settingsChangedAt = Date.now();
        void shareSettings('watch');
      }
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
    sheetTitle: current ? (current.getTitle() || (allTools().find((t) => t.id === sheetTool) || {}).name || '') : '',
    canBack: current ? current.navigationHistory.canGoBack() : false,
    canForward: current ? current.navigationHistory.canGoForward() : false,
    panelKey: keys.label(settings.panelKey),
    tools: TOOLS.map((t) => ({ id: t.id, name: t.name, key: keys.label(settings.toolKeys[t.id]) })),
    pages: myPages().map((p) => ({ id: pageId(p.url), name: p.name, url: p.url, key: keys.label(settings.toolKeys[pageId(p.url)]) })),
    pagesLine: state.pages.line, extras: state.extras,
    quick: { fullscreen: !!(win && win.isFullScreen()), muted: !!settings.muted, dark: !!settings.dark },
    hasCode: !!settings.historyCode,
    history: state.history, update: state.update, settingsShare: state.settings, backup: state.backup
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
ipcMain.handle('ui:shareNow', () => shareAll('periodic'));
ipcMain.handle('ui:candidates', async () => {
  try {
    const user = currentUser || await accountName();
    if (!user) { return { error: 'Log in to the game first.' }; }
    currentUser = user;
    const list = await settingsSync.candidates(storeRequest, storeFor(user));
    return { list: list.map((c) => ({ ...c, mine: c.device === DEVICE })) };
  } catch (e) {
    return { error: (e && e.message) || String(e) };
  }
});
ipcMain.handle('ui:choose', async (event, device) => {
  try {
    await settingsSync.choose(storeRequest, storeFor(currentUser), device, Date.now());
    // This computer's own settings have nothing to apply; another device's need the game reloaded.
    rememberSettings(currentUser, null);
    await shareSettings('chosen');
    return '';
  } catch (e) {
    return (e && e.message) || String(e);
  }
});
ipcMain.handle('ui:applySettings', () => shareSettings('apply'));
ipcMain.handle('ui:changePage', (event, index, entry) => changePage(index, entry));
ipcMain.handle('ui:copyCaught', () => copyCaught());
ipcMain.handle('ui:copyTeam', (event, slot) => copyTeam(slot));
ipcMain.handle('ui:checkUpdates', () => { setPanel(false); return checkForUpdates(true); });
ipcMain.handle('ui:reload', () => { setPanel(false); game.webContents.reload(); });
ipcMain.handle('ui:nav', (event, action) => {
  const tool = allTools().find((t) => t.id === sheetTool);
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
