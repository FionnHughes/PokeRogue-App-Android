// Shared settings through the store: the game keeps its settings only in the browser.
// The same rules run in the phone app (SettingsHub.java).
//
// Until the player chooses, every device leaves its settings as a candidate and the
// store has no shared settings. After the choice, a device that changed its settings
// since its last exchange sends them; otherwise it takes the shared ones. When both
// changed, the later change wins. A few settings belong to the device (touch controls,
// vibration) and are never taken from another device.
const crypto = require('crypto');

// What the game keeps in localStorage that counts as settings (game version 1.12).
const KEYS = ['settings', 'settingsKeyboard', 'settingsGamepad', 'mappingConfigs', 'prLang', 'tutorials', 'seenDialogues'];
// Entries of "settings" that stay as each device has them.
const OWN = ['TOUCH_CONTROLS', 'MOVE_TOUCH_CONTROLS', 'VIBRATION', 'gameVersion'];

function stable(value) {
  if (Array.isArray(value)) { return '[' + value.map(stable).join(',') + ']'; }
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort().map((k) => JSON.stringify(k) + ':' + stable(value[k])).join(',') + '}';
  }
  return JSON.stringify(value);
}

/** The settings as compared between devices: parsed, keys sorted, device-own entries left out. */
function normalize(data) {
  const out = {};
  for (const key of KEYS) {
    const text = data && data[key];
    if (text === null || text === undefined || text === '') { continue; }
    try {
      const value = JSON.parse(text);
      if (key === 'settings' && value && typeof value === 'object') { for (const own of OWN) { delete value[own]; } }
      out[key] = stable(value);
    } catch (e) {
      out[key] = String(text);
    }
  }
  return out;
}

function hash(data) {
  return crypto.createHash('sha256').update(stable(normalize(data))).digest('hex');
}

/** What to write so this device uses the shared settings but keeps its own entries. key -> text. */
function merge(local, shared) {
  const out = {};
  for (const key of KEYS) {
    const text = shared[key];
    if (text === null || text === undefined || text === '') { continue; }
    if (key !== 'settings') { out[key] = String(text); continue; }
    try {
      const value = JSON.parse(text);
      let mine = {};
      try { mine = JSON.parse(local.settings || '{}') || {}; } catch (e) { mine = {}; }
      for (const own of OWN) {
        if (own in mine) { value[own] = mine[own]; } else { delete value[own]; }
      }
      out[key] = JSON.stringify(value);
    } catch (e) {
      out[key] = String(text);
    }
  }
  return out;
}

/**
 * One exchange. io: { request(method, url, body) -> {status, text}, store (ends in "/"),
 * device, label, local (key -> text), last ({updated, hash} or null), changedAt (ms
 * this device noticed its own change, 0 if not known), now (ms) }.
 * Resolves to { action, shared, last, write }:
 *   'choose'  no shared settings yet; this device's are a candidate now
 *   'same'    nothing to do
 *   'pushed'  this device's change is the shared settings now
 *   'apply'   write `write` (then keep `last`) to use the shared settings
 */
async function exchange(io) {
  const call = async (method, url, body, okStatuses = []) => {
    const answer = await io.request(method, url, body);
    if (answer.status === 401) { throw new Error('the store did not accept the code'); }
    if ((answer.status < 200 || answer.status >= 300) && !okStatuses.includes(answer.status)) {
      throw new Error('the store answered ' + answer.status);
    }
    return answer;
  };
  const mine = { updated: io.now, from: io.label, data: pick(io.local) };
  // Kept fresh, so a later "choose again" offers what each device has now.
  await call('PUT', io.store + 'settings/candidates/' + io.device, JSON.stringify(mine));

  const got = await call('GET', io.store + 'settings', undefined, [404]);
  if (got.status === 404) { return { action: 'choose', shared: null, last: io.last, write: null }; }
  let shared = JSON.parse(got.text);
  const localHash = hash(io.local);
  const sharedHash = hash(shared.data);
  if (localHash === sharedHash) {
    return { action: 'same', shared, last: { updated: shared.updated, hash: sharedHash }, write: null };
  }
  const localChanged = !!io.last && localHash !== io.last.hash;
  const sharedChanged = !io.last || sharedHash !== io.last.hash;
  const localWins = localChanged && (!sharedChanged || (io.changedAt || 0) > shared.updated);
  if (localWins) {
    const updated = Math.max(io.changedAt || io.now, shared.updated + 1);
    const sent = await call('PUT', io.store + 'settings', JSON.stringify({ ...mine, updated }), [409]);
    if (sent.status !== 409) {
      return { action: 'pushed', shared: { ...mine, updated }, last: { updated, hash: localHash }, write: null };
    }
    shared = JSON.parse(sent.text); // changed elsewhere in the meantime: that change is later
  }
  return { action: 'apply', shared, last: { updated: shared.updated, hash: hash(shared.data) }, write: merge(io.local, shared.data) };
}

/** Makes one device's candidate the shared settings. Resolves to them. */
async function choose(request, store, device, now) {
  const got = await request('GET', store + 'settings/candidates/' + device);
  if (got.status !== 200) { throw new Error('that device\'s settings are not in the store (' + got.status + ')'); }
  const candidate = JSON.parse(got.text);
  const shared = { updated: now, from: candidate.from, data: candidate.data };
  const sent = await request('PUT', store + 'settings', JSON.stringify(shared));
  if (sent.status < 200 || sent.status >= 300) { throw new Error('the store answered ' + sent.status); }
  return shared;
}

async function candidates(request, store) {
  const got = await request('GET', store + 'settings/candidates/');
  if (got.status !== 200) { throw new Error('the store answered ' + got.status); }
  return JSON.parse(got.text).candidates || [];
}

function pick(local) {
  const out = {};
  for (const key of KEYS) { if (local[key] !== null && local[key] !== undefined) { out[key] = String(local[key]); } }
  return out;
}

module.exports = { exchange, choose, candidates, normalize, hash, merge, KEYS, OWN };
