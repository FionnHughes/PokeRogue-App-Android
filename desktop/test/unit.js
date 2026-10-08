// Checks for the parts that need no window: key rules and the run history exchange.
const assert = require('assert');
const keys = require('../src/keys');
const history = require('../src/history');

let failed = 0;
async function check(name, fn) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}

(async () => {
  const defaults = keys.gameKeys(null);

  await check('game keys: defaults include T, Z and the arrows, not P or Tab', () => {
    assert.strictEqual(defaults.KeyT, 'cycle form');
    assert.strictEqual(defaults.KeyZ, 'action');
    assert.strictEqual(defaults.ArrowUp, 'up');
    assert.strictEqual(defaults.KeyP, undefined);
    assert.strictEqual(defaults.Tab, undefined);
  });

  await check('game keys: a changed layout replaces the defaults', () => {
    const used = keys.gameKeys(JSON.stringify({ default: { custom: { KEY_P: 'BUTTON_STATS', KEY_T: -1 } } }));
    assert.strictEqual(used.KeyP, 'stats');
    assert.strictEqual(used.KeyT, undefined);
  });

  await check('game keys: an unreadable layout falls back to the defaults', () => {
    assert.deepStrictEqual(keys.gameKeys('{not json'), defaults);
  });

  await check('refuse: a key the game uses, with what it does', () => {
    const why = keys.refuse({ code: 'KeyT' }, defaults, []);
    assert.match(why, /The game uses T for "cycle form"/);
  });

  await check('refuse: the same key held with Ctrl is fine', () => {
    assert.strictEqual(keys.refuse({ code: 'KeyT', ctrl: true }, defaults, []), '');
  });

  await check('refuse: a free key is fine, a taken one names its owner', () => {
    assert.strictEqual(keys.refuse({ code: 'KeyP' }, defaults, []), '');
    const why = keys.refuse({ code: 'KeyP' }, defaults, [{ name: 'Pokedex', binding: { code: 'KeyP' } }]);
    assert.match(why, /P already opens Pokedex/);
  });

  await check('refuse: modifiers alone, Escape and the app keys', () => {
    assert.ok(keys.refuse({ code: 'ShiftLeft' }, defaults, []));
    assert.ok(keys.refuse({ code: 'Escape' }, defaults, []));
    assert.match(keys.refuse({ code: 'F11' }, defaults, []), /full screen/);
  });

  await check('labels', () => {
    assert.strictEqual(keys.label({ code: 'KeyP' }), 'P');
    assert.strictEqual(keys.label({ code: 'Digit3', ctrl: true }), 'Ctrl+3');
    assert.strictEqual(keys.label({ code: 'Tab' }), 'Tab');
    assert.strictEqual(keys.label({ code: 'ArrowLeft', alt: true }), 'Alt+Left');
    assert.strictEqual(keys.label({ code: 'Slash' }), '/');
  });

  // A store in memory that behaves like modern-patch/server/history_hub.py for one account.
  function fakeStore(runs, code) {
    const kept = { ...runs };
    const request = async (method, url, body, given) => {
      if (given !== code) { return { status: 401, text: '' }; }
      const key = url.split('/').pop();
      if (method === 'GET' && key === '') {
        return { status: 200, text: JSON.stringify({ runs: Object.keys(kept).sort((a, b) => b - a) }) };
      }
      if (method === 'GET') { return key in kept ? { status: 200, text: JSON.stringify(kept[key]) } : { status: 404, text: '' }; }
      if (!(key in kept)) { kept[key] = JSON.parse(body); }
      return { status: 204, text: '' };
    };
    return { kept, request };
  }
  const run = (wave) => ({ entry: { waveIndex: wave, timestamp: 1 }, isVictory: false, isFavorite: false });

  await check('exchange: sends what the store lacks, fetches what the game lacks', async () => {
    const store = fakeStore({ 1790000001000: run(5), 1790000002000: run(6) }, 'secretsecret');
    let stored = history.encrypt({ 1790000003000: run(7), 1790000001000: run(5) });
    const result = await history.exchange('https://x/pr/h/me/', {
      request: (m, u, b) => store.request(m, u, b, 'secretsecret'),
      readStored: async () => stored,
      writeStored: async (c) => { stored = c; }
    });
    assert.deepStrictEqual(result, { received: 1, sent: 1, inBrowser: 2, inStore: 2 });
    assert.deepStrictEqual(Object.keys(history.decrypt(stored)).sort(), ['1790000001000', '1790000002000', '1790000003000']);
    assert.ok('1790000003000' in store.kept);
  });

  await check('exchange: nothing stored here yet', async () => {
    const store = fakeStore({ 1790000001000: run(5) }, 'c');
    let stored = '';
    const result = await history.exchange('https://x/', {
      request: (m, u, b) => store.request(m, u, b, 'c'), readStored: async () => stored, writeStored: async (c) => { stored = c; }
    });
    assert.strictEqual(result.received, 1);
    assert.strictEqual(Object.keys(history.decrypt(stored)).length, 1);
  });

  await check('exchange: keeps only the newest 25 when fetching', async () => {
    const many = {};
    for (let i = 0; i < 30; i++) { many[String(1790000000000 + i * 1000)] = run(i); }
    const store = fakeStore(many, 'c');
    let stored = '';
    const result = await history.exchange('https://x/', {
      request: (m, u, b) => store.request(m, u, b, 'c'), readStored: async () => stored, writeStored: async (c) => { stored = c; }
    });
    assert.strictEqual(result.received, 25);
    assert.ok(!('1790000000000' in history.decrypt(stored)));
  });

  await check('exchange: a run recorded meanwhile is kept', async () => {
    const store = fakeStore({ 1790000001000: run(5) }, 'c');
    let reads = 0;
    let stored = history.encrypt({ 1790000002000: run(6) });
    const result = await history.exchange('https://x/', {
      request: (m, u, b) => store.request(m, u, b, 'c'),
      readStored: async () => (++reads === 2 ? history.encrypt({ 1790000002000: run(6), 1790000009000: run(9) }) : stored),
      writeStored: async (c) => { stored = c; }
    });
    assert.strictEqual(result.received, 1);
    assert.ok('1790000009000' in history.decrypt(stored));
  });

  await check('exchange: a wrong code is reported and nothing is written', async () => {
    const store = fakeStore({}, 'right');
    let wrote = false;
    await assert.rejects(history.exchange('https://x/', {
      request: (m, u, b) => store.request(m, u, b, 'wrong'), readStored: async () => '', writeStored: async () => { wrote = true; }
    }), /did not accept the code/);
    assert.strictEqual(wrote, false);
  });

  await check('exchange: an unreadable stored history stops it', async () => {
    const store = fakeStore({ 1790000001000: run(5) }, 'c');
    await assert.rejects(history.exchange('https://x/', {
      request: (m, u, b) => store.request(m, u, b, 'c'), readStored: async () => 'garbage', writeStored: async () => {}
    }), /could not be read/);
  });

  // ---- shared settings ----
  const sync = require('../src/settings-sync');
  // An in-memory store with the server's rules for settings.
  function settingsStore() {
    const files = {};
    const request = async (method, url, body) => {
      const path = url.replace('https://x/', '');
      if (method === 'PUT') {
        const value = JSON.parse(body);
        if (path === 'settings' && files.settings && files.settings.updated > value.updated) {
          return { status: 409, text: JSON.stringify(files.settings) };
        }
        files[path] = value;
        return { status: 204, text: '' };
      }
      if (path === 'settings/candidates/') {
        return { status: 200, text: JSON.stringify({ candidates: Object.keys(files).filter((k) => k.startsWith('settings/candidates/'))
          .map((k) => ({ device: k.split('/').pop(), from: files[k].from, updated: files[k].updated })) }) };
      }
      return path in files ? { status: 200, text: JSON.stringify(files[path]) } : { status: 404, text: '' };
    };
    return { files, request };
  }
  const laptop = { settings: JSON.stringify({ GAME_SPEED: 5, MASTER_VOLUME: 3, gameVersion: '1.12' }), prLang: 'en' };
  const phone = { settings: JSON.stringify({ GAME_SPEED: 2, TOUCH_CONTROLS: 1, VIBRATION: 0 }), prLang: 'en' };
  const ex = (store, device, local, last, changedAt, now) => sync.exchange({
    request: store.request, store: 'https://x/', device, label: device, local, last, changedAt, now });

  await check('settings: nothing chosen yet, each device leaves a candidate', async () => {
    const store = settingsStore();
    assert.strictEqual((await ex(store, 'pc-a', laptop, null, 0, 10)).action, 'choose');
    assert.strictEqual((await ex(store, 'phone-offline', phone, null, 0, 11)).action, 'choose');
    const list = await sync.candidates(store.request, 'https://x/');
    assert.deepStrictEqual(list.map((c) => c.device).sort(), ['pc-a', 'phone-offline']);
  });

  await check('settings: choosing the laptop\'s, the phone takes them but keeps its touch controls', async () => {
    const store = settingsStore();
    await ex(store, 'pc-a', laptop, null, 0, 10);
    await ex(store, 'phone-offline', phone, null, 0, 11);
    await sync.choose(store.request, 'https://x/', 'pc-a', 20);
    const atPhone = await ex(store, 'phone-offline', phone, null, 0, 30);
    assert.strictEqual(atPhone.action, 'apply');
    const written = JSON.parse(atPhone.write.settings);
    assert.strictEqual(written.GAME_SPEED, 5);
    assert.strictEqual(written.TOUCH_CONTROLS, 1);
    assert.strictEqual(written.VIBRATION, 0);
    // after writing, the next exchange has nothing to do
    const again = await ex(store, 'phone-offline', { ...phone, ...atPhone.write }, atPhone.last, 0, 31);
    assert.strictEqual(again.action, 'same');
    const atLaptop = await ex(store, 'pc-a', laptop, null, 0, 32);
    assert.strictEqual(atLaptop.action, 'same');
  });

  await check('settings: a change on one device reaches the other', async () => {
    const store = settingsStore();
    await ex(store, 'pc-a', laptop, null, 0, 10);
    await sync.choose(store.request, 'https://x/', 'pc-a', 20);
    let last = (await ex(store, 'pc-a', laptop, null, 0, 21)).last;
    const louder = { ...laptop, settings: JSON.stringify({ GAME_SPEED: 5, MASTER_VOLUME: 9 }) };
    const pushed = await ex(store, 'pc-a', louder, last, 40, 41);
    assert.strictEqual(pushed.action, 'pushed');
    const atPhone = await ex(store, 'phone-online', phone, null, 0, 50);
    assert.strictEqual(atPhone.action, 'apply');
    assert.strictEqual(JSON.parse(atPhone.write.settings).MASTER_VOLUME, 9);
  });

  await check('settings: both changed, the later change wins', async () => {
    const store = settingsStore();
    await ex(store, 'pc-a', laptop, null, 0, 10);
    await sync.choose(store.request, 'https://x/', 'pc-a', 20);
    const lastA = (await ex(store, 'pc-a', laptop, null, 0, 21)).last;
    const lastB = (await ex(store, 'phone-online', phone, null, 0, 22)).last;
    const phoneSide = { ...phone, settings: JSON.stringify({ GAME_SPEED: 1, MASTER_VOLUME: 3, TOUCH_CONTROLS: 1 }) };
    // the phone changed at 30 but only exchanges at 100; the laptop changed at 60
    await ex(store, 'pc-a', { ...laptop, settings: JSON.stringify({ GAME_SPEED: 4, MASTER_VOLUME: 3 }) }, lastA, 60, 61);
    const atPhone = await ex(store, 'phone-online', phoneSide, lastB, 30, 100);
    assert.strictEqual(atPhone.action, 'apply');
    assert.strictEqual(JSON.parse(atPhone.write.settings).GAME_SPEED, 4);
  });

  console.log(failed ? failed + ' failed' : 'all passed');
  process.exit(failed ? 1 : 0);
})();
