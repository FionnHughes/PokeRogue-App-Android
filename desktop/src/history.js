// Run history sharing, the same exchange as the phone app (HistoryHub.java) and the
// laptop userscript: send the store the finished runs it lacks, fetch the ones this
// game lacks. The game keeps run history only in the browser, under
// runHistoryData_<account>, encrypted with CryptoJS AES and the passphrase below.
const CryptoJS = require('crypto-js');

const GAME_STORAGE_KEY = 'x0i2O7WRiANTqPmZ';
const LIMIT = 25; // the game keeps this many finished runs

function isKey(key) { return /^[0-9]{10,16}$/.test(key); }

function decrypt(cipher) {
  const runs = JSON.parse(CryptoJS.AES.decrypt(cipher, GAME_STORAGE_KEY).toString(CryptoJS.enc.Utf8));
  return runs && typeof runs === 'object' && !Array.isArray(runs) ? runs : {};
}
/** Any text the game stored with its passphrase, decrypted; '' if it cannot be read. */
function decryptText(cipher) {
  if (!cipher) { return ''; }
  try { return CryptoJS.AES.decrypt(cipher, GAME_STORAGE_KEY).toString(CryptoJS.enc.Utf8); } catch (e) { return ''; }
}
function encrypt(runs) {
  return CryptoJS.AES.encrypt(JSON.stringify(runs), GAME_STORAGE_KEY).toString();
}

/**
 * One exchange. io supplies the outside world:
 *   request(method, url, body) -> Promise<{ status, text }>
 *   readStored() -> Promise<string>   the game's stored history for the account ('' if none)
 *   writeStored(cipher) -> Promise
 * store ends in "/" and names the account. Resolves to
 * { received, sent, inBrowser, inStore }; throws with a readable message.
 */
async function exchange(store, io) {
  const read = async () => {
    const kept = await io.readStored();
    if (!kept) { return {}; }
    try {
      return decrypt(kept);
    } catch (e) {
      // carrying on would replace a history this app cannot read
      throw new Error("this game's run history could not be read");
    }
  };
  const call = async (method, url, body) => {
    const answer = await io.request(method, url, body);
    if (answer.status === 401) { throw new Error('the store did not accept the code'); }
    if (answer.status < 200 || answer.status >= 300) { throw new Error('the store answered ' + answer.status); }
    return answer;
  };

  const mine = await read();
  const listed = JSON.parse((await call('GET', store)).text).runs;
  const theirs = Array.isArray(listed) ? listed.filter(isKey) : [];
  const toSend = Object.keys(mine).filter((key) => isKey(key) && !theirs.includes(key));
  // Only runs that make it into the newest ones are worth fetching.
  const newest = theirs.concat(toSend).sort((a, b) => Number(b) - Number(a)).slice(0, LIMIT);
  const toFetch = newest.filter((key) => !(key in mine));

  let sent = 0;
  for (const key of toSend) {
    await call('PUT', store + key, JSON.stringify(mine[key]));
    sent++;
  }
  const fetched = {};
  for (const key of toFetch) {
    fetched[key] = JSON.parse((await call('GET', store + key)).text);
  }
  if (toFetch.length) {
    const merged = {};
    for (const key of newest) { merged[key] = key in mine ? mine[key] : fetched[key]; }
    // The game may have recorded a run while this was running; that run must not be lost.
    const now = await read();
    for (const key of Object.keys(now)) { if (!(key in mine) && !(key in merged)) { merged[key] = now[key]; } }
    await io.writeStored(encrypt(merged));
  }
  return { received: toFetch.length, sent, inBrowser: Object.keys(mine).filter(isKey).length, inStore: theirs.length };
}

module.exports = { exchange, encrypt, decrypt, decryptText, isKey, LIMIT };
