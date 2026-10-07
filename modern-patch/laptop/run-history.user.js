// ==UserScript==
// @name         Run history sharing
// @namespace    https://fionnhughes.dev/pr/
// @version      1.0
// @description  Shares my finished runs between my devices through my own store.
// @match        https://pokerogue.net/*
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @connect      fionnhughes.dev
// @require      https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.2.0/crypto-js.min.js
// @updateURL    https://raw.githubusercontent.com/FionnHughes/PokeRogue-App-Android/fix/android-save-import/modern-patch/laptop/run-history.user.js
// @downloadURL  https://raw.githubusercontent.com/FionnHughes/PokeRogue-App-Android/fix/android-save-import/modern-patch/laptop/run-history.user.js
// ==/UserScript==

// The game keeps run history in each browser and never sends it to its server, so a
// run finished here is unknown to my phone, and the other way round. This script
// sends the store the finished runs it lacks and fetches the ones this browser lacks.
// The patched Android app does the same (modern-patch/src/importfix/HistoryHub.java),
// against the same store (modern-patch/server/history_hub.py).
//
// It needs the store's code once: it asks on first use, and the userscript manager's
// menu has "Set the run history code" to change it.
(function() {
  'use strict';

  var STORE = GM_getValue('store', 'https://fionnhughes.dev/pr/h/'); // ends in "/"
  var GAME_STORAGE_KEY = 'x0i2O7WRiANTqPmZ'; // the passphrase the game uses for what it keeps in the browser
  var PREFIX = 'runHistoryData_';
  var LIMIT = 25; // the game keeps this many finished runs
  var FIRST_MS = 8000;
  var WATCH_MS = 15000; // how often to look whether the game has recorded a new run
  var REPEAT_MS = 180000; // how often to look for runs from other devices

  function isKey(key) { return /^[0-9]{10,16}$/.test(key); }

  // One request to the store. Resolves to { status, text }.
  function request(method, url, body) {
    return new Promise(function(resolve, reject) {
      GM_xmlhttpRequest({
        method: method,
        url: url,
        data: body,
        headers: { Authorization: 'Bearer ' + GM_getValue('code', ''), 'Content-Type': 'application/json' },
        timeout: 20000,
        onload: function(answer) { resolve({ status: answer.status, text: answer.responseText || '' }); },
        onerror: function() { reject(new Error('the store could not be reached')); },
        ontimeout: function() { reject(new Error('the store did not answer')); }
      });
    }).then(function(answer) {
      if (answer.status === 401) { throw new Error('the store did not accept the code'); }
      if (answer.status < 200 || answer.status >= 300) { throw new Error('the store answered ' + answer.status); }
      return answer;
    });
  }

  // The name of the logged-in account, which is part of the key the game stores under.
  function whoAmI() {
    var token = '';
    document.cookie.split(';').forEach(function(part) {
      var pair = part.trim();
      if (pair.indexOf('pokerogue_sessionId=') === 0 && !token) { token = pair.slice('pokerogue_sessionId='.length); }
    });
    if (!token) { return Promise.resolve(''); }
    return fetch('https://api.pokerogue.net/account/info', { headers: { Authorization: token } })
      .then(function(response) { return response.ok ? response.json() : null; })
      .then(function(info) { return (info && info.username) || ''; })
      .catch(function() { return ''; });
  }

  function readHistory(user) {
    try {
      var kept = localStorage.getItem(PREFIX + user);
      var runs = kept ? JSON.parse(CryptoJS.AES.decrypt(kept, GAME_STORAGE_KEY).toString(CryptoJS.enc.Utf8)) : {};
      return runs && typeof runs === 'object' && !Array.isArray(runs) ? runs : {};
    } catch (e) {
      return {};
    }
  }
  function writeHistory(user, runs) {
    localStorage.setItem(PREFIX + user, CryptoJS.AES.encrypt(JSON.stringify(runs), GAME_STORAGE_KEY).toString());
  }

  var busy = false;
  // The account and its stored history as of the last exchange, to notice when the game adds a run.
  var watched = '';
  var lastSeen = null;

  // Sends what the store lacks, fetches what this browser lacks. Resolves to a line saying what happened.
  function exchange() {
    if (busy) { return Promise.resolve('already running'); }
    if (!GM_getValue('code', '')) { return Promise.resolve('no code set'); }
    busy = true;
    var user = '';
    var mine = {};
    var sent = 0;
    var received = 0;
    return whoAmI().then(function(name) {
      if (!name) { throw new Error('not logged in'); }
      user = name;
      mine = readHistory(user);
      return request('GET', STORE);
    }).then(function(answer) {
      var theirs = JSON.parse(answer.text).runs || [];
      var toSend = Object.keys(mine).filter(function(key) { return isKey(key) && theirs.indexOf(key) < 0; });
      // Only runs that make it into the newest ones are worth fetching.
      var newest = theirs.concat(toSend).filter(isKey)
        .sort(function(a, b) { return Number(b) - Number(a); }).slice(0, LIMIT);
      var toFetch = newest.filter(function(key) { return !(key in mine); });
      var merged = {};
      var chain = Promise.resolve();
      toSend.forEach(function(key) {
        chain = chain.then(function() { return request('PUT', STORE + key, JSON.stringify(mine[key])); })
          .then(function() { sent++; });
      });
      toFetch.forEach(function(key) {
        chain = chain.then(function() { return request('GET', STORE + key); })
          .then(function(run) { merged[key] = JSON.parse(run.text); received++; });
      });
      return chain.then(function() {
        if (!received) { return; }
        newest.forEach(function(key) { if (key in mine) { merged[key] = mine[key]; } });
        // The game may have recorded a run while this was running; that run must not be lost.
        var now = readHistory(user);
        Object.keys(now).forEach(function(key) { if (!(key in mine) && !(key in merged)) { merged[key] = now[key]; } });
        writeHistory(user, merged);
      });
    }).then(function() {
      watched = user;
      lastSeen = localStorage.getItem(PREFIX + user);
      return received + ' received, ' + sent + ' sent';
    }).catch(function(e) {
      return 'failed: ' + ((e && e.message) || e);
    }).then(function(line) {
      busy = false;
      console.log('[run history] ' + line);
      return line;
    });
  }

  function askCode() {
    var typed = prompt('Code of the run history store (the same one as in the phone app).\nLeave empty to turn sharing off.',
      GM_getValue('code', ''));
    if (typed === null) { return; }
    GM_setValue('code', typed.trim());
    GM_setValue('asked', true);
    if (typed.trim()) { exchange().then(function(line) { alert('Run history: ' + line); }); }
  }

  GM_registerMenuCommand('Share run history now', function() {
    exchange().then(function(line) { alert('Run history: ' + line); });
  });
  GM_registerMenuCommand('Set the run history code', askCode);

  if (!GM_getValue('code', '') && !GM_getValue('asked', false)) {
    GM_setValue('asked', true);
    setTimeout(askCode, FIRST_MS);
  } else {
    setTimeout(exchange, FIRST_MS);
  }
  setInterval(exchange, REPEAT_MS);
  // The game records a finished run by rewriting its stored history; that is the moment to send it.
  setInterval(function() {
    if (!busy && watched && localStorage.getItem(PREFIX + watched) !== lastSeen) { exchange(); }
  }, WATCH_MS);
})();
