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

  console.log(failed ? failed + ' failed' : 'all passed');
  process.exit(failed ? 1 : 0);
})();
