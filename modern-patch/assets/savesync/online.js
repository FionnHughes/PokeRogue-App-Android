// Helper page on the official site's origin (https://pokerogue.net). It talks to the
// official server exactly as the game does: the login cookie as the Authorization
// header and a fresh client session id.
//
// Loaded plain, it fetches the save data, the runs in progress and the account name,
// and reads that account's run history. The game keeps run history in the browser
// only, under runHistoryData_<name>, encrypted.
//
// The online game also keeps its own copies of the save data and the runs in progress
// in the browser (data_<name>, sessionData<slot>_<name>, encrypted), and only sends
// them to the server every few minutes. Those copies are reported too, because they
// can be ahead of the server; the game itself prefers them when they are newer.
//
// Loaded with "?upload", it waits for data to send (see upload below).
(function() {
  var ROOT = 'https://api.pokerogue.net/';
  var SLOTS = 5;
  var HISTORY_PREFIX = 'runHistoryData_';
  var host = window.saveSyncHost;

  window.onerror = function(message, source, line) {
    try { host.onPageError('online page: ' + message + ' (line ' + line + ')'); } catch (e) {}
  };

  // Every value stored for the login cookie. A stale duplicate can sit next to the
  // live one, so each is tried until the server accepts one.
  function tokens(name) {
    var found = [];
    var parts = document.cookie.split(';');
    for (var i = 0; i < parts.length; i++) {
      var c = parts[i].trim();
      if (c.indexOf(name + '=') !== 0) { continue; }
      var value = c.slice(name.length + 1);
      if (value && found.indexOf(value) < 0) { found.push(value); }
    }
    return found;
  }
  var list = tokens('pokerogue_sessionId');
  // The server takes updates only from the client session that asked last. Using the
  // running online game's own id, when the app knows it, keeps that game's session valid.
  var id = '';
  try { id = String(host.clientId() || ''); } catch (e) {}
  if (!/^[A-Za-z0-9]{32}$/.test(id)) {
    var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    id = '';
    for (var n = 0; n < 32; n++) { id += alphabet.charAt(Math.floor(Math.random() * alphabet.length)); }
  }

  function headers(index) { return { Authorization: list[index], 'Content-Type': 'application/json' }; }
  function problem(e) { return String((e && e.message) || e).slice(0, 120); }
  function saveCacheKey(username) { return 'data_' + username; }
  function sessionCacheKey(slot, username) { return 'sessionData' + (slot || '') + '_' + username; }
  function stored(key) { try { return localStorage.getItem(key) || ''; } catch (e) { return ''; } }

  // Fetches the save data with each stored login in turn. Resolves to
  // { index, status, text }; index is the login that worked.
  function getSave(index) {
    return fetch(ROOT + 'savedata/system/get?clientSessionId=' + id, { headers: headers(index) })
      .then(function(response) {
        return response.text().then(function(text) {
          if (response.status === 401 && index + 1 < list.length) { return getSave(index + 1); }
          return { index: index, status: response.status, text: response.ok ? text : text.trim().slice(0, 200) };
        });
      });
  }

  // The account name is part of the run history's storage key. If the server does not
  // say who is logged in, a single stored history is taken to be theirs.
  function onlyStoredUser() {
    var names = [];
    for (var i = 0; i < localStorage.length; i++) {
      var key = localStorage.key(i);
      if (key.indexOf(HISTORY_PREFIX) === 0 && key !== HISTORY_PREFIX + 'Guest') { names.push(key.slice(HISTORY_PREFIX.length)); }
    }
    return names.length === 1 ? names[0] : '';
  }
  function whoAmI(index) {
    return fetch(ROOT + 'account/info', { headers: headers(index) })
      .then(function(response) { return response.ok ? response.json() : null; })
      .then(function(info) { return (info && info.username) || onlyStoredUser(); })
      .catch(function() { return onlyStoredUser(); });
  }
  // One run in progress: its text, '' for an empty slot, or null if the server failed.
  function getSession(index, slot) {
    return fetch(ROOT + 'savedata/session/get?slot=' + slot + '&clientSessionId=' + id, { headers: headers(index) })
      .then(function(response) {
        if (response.status === 404) { return ''; }
        return response.text().then(function(text) { return response.ok && text.charAt(0) === '{' ? text : null; });
      })
      .catch(function() { return null; });
  }

  function fetchEverything() {
    var report = {
      status: 0, problem: '', username: '', save: '', sessions: ['', '', '', '', ''], sessionsKnown: true, history: '',
      cache: { save: '', sessions: ['', '', '', '', ''] }
    };
    function send() { host.onOnline(JSON.stringify(report)); }
    if (!list.length) { host.log('no login cookie'); send(); return; }
    host.log('asking the server for the save data');
    getSave(0).then(function(got) {
      report.status = got.status;
      if (got.status !== 200) { report.problem = got.text; host.log('server answered ' + got.status); send(); return; }
      report.save = got.text;
      host.log('save data received (' + Math.round(got.text.length / 1024) + ' KB), asking for runs in progress');
      var slots = [];
      for (var slot = 0; slot < SLOTS; slot++) { slots.push(getSession(got.index, slot)); }
      return Promise.all([whoAmI(got.index), Promise.all(slots)]).then(function(results) {
        report.username = results[0] || '';
        results[1].forEach(function(text, slot) {
          if (text === null) { report.sessionsKnown = false; } else { report.sessions[slot] = text; }
        });
        if (report.username) {
          report.history = stored(HISTORY_PREFIX + report.username);
          report.cache.save = stored(saveCacheKey(report.username));
          for (var s = 0; s < SLOTS; s++) { report.cache.sessions[s] = stored(sessionCacheKey(s, report.username)); }
        }
        host.log('account ' + (report.username || 'unknown') + ', '
          + report.sessions.filter(function(s) { return !!s; }).length + ' runs in progress, run history '
          + (report.history ? Math.round(report.history.length / 1024) + ' KB' : 'none'));
        send();
      });
    }).catch(function(e) {
      report.status = -1;
      report.problem = problem(e);
      host.log('request failed: ' + report.problem);
      send();
    });
  }

  // payload: { save, sessions[5], username, history } with '' for "leave alone".
  // history is the run history as the game stores it (already encrypted).
  // The save data goes first. If the server refuses it, nothing else is sent, because
  // the runs in progress and the run history belong with that save.
  function upload(payload) {
    var result = { status: 0, save: null, sessions: [null, null, null, null, null], history: false };
    function done() { host.onUploaded(JSON.stringify(result)); }
    function writeHistory() {
      if (payload.history && payload.username) {
        localStorage.setItem(HISTORY_PREFIX + payload.username, payload.history);
        result.history = true;
        host.log('run history stored');
      }
    }
    var slots = [];
    for (var slot = 0; slot < SLOTS; slot++) { if (payload.sessions && payload.sessions[slot]) { slots.push(slot); } }
    if (!payload.save && !slots.length) {
      result.status = 200;
      try { writeHistory(); } catch (e) { result.historyProblem = problem(e); }
      done();
      return;
    }
    if (!list.length) { done(); return; }

    function post(index, path, body) {
      return fetch(ROOT + path, { method: 'POST', headers: headers(index), body: body }).then(function(response) {
        return response.text().then(function(text) { return { status: response.status, ok: response.ok, body: text.trim().slice(0, 200) }; });
      });
    }
    function sendSessions(index, position, ids) {
      if (position >= slots.length) { return Promise.resolve(); }
      var slot = slots[position];
      host.log('sending run in progress, slot ' + (slot + 1));
      // The same request the game makes when it saves a run.
      return post(index, 'savedata/session/update?slot=' + slot + ids + '&clientSessionId=' + id, payload.sessions[slot]).then(function(sent) {
        result.sessions[slot] = { status: sent.status, body: sent.body };
        host.log('server answered ' + sent.status + (sent.body ? ': ' + sent.body : ''));
        // The online game reads its own stored copy first; drop it so it asks the server.
        if (sent.ok && payload.username) { localStorage.removeItem(sessionCacheKey(slot, payload.username)); }
        return sendSessions(index, position + 1, ids);
      });
    }
    // The game sends the account's trainer and secret id along with a run.
    function idsOf(saveText) {
      try {
        var d = JSON.parse(saveText);
        return '&trainerId=' + encodeURIComponent(d.trainerId) + '&secretId=' + encodeURIComponent(d.secretId);
      } catch (e) { return ''; }
    }
    // The server only takes updates from the session that last fetched the save.
    host.log('opening a session with the server');
    getSave(0).then(function(got) {
      result.status = got.status;
      if (got.status !== 200) { result.problem = got.text; return; }
      var saveStep = Promise.resolve(true);
      if (payload.save) {
        host.log('sending save data');
        saveStep = post(got.index, 'savedata/system/update?clientSessionId=' + id, payload.save).then(function(sent) {
          result.save = { status: sent.status, body: sent.body };
          host.log('server answered ' + sent.status + (sent.body ? ': ' + sent.body : ''));
          // As with runs in progress: drop the game's stored copy so it asks the server.
          if (sent.ok && payload.username) { localStorage.removeItem(saveCacheKey(payload.username)); }
          return sent.ok;
        });
      }
      return saveStep.then(function(ok) {
        if (!ok) { return; }
        return sendSessions(got.index, 0, idsOf(payload.save || got.text)).then(function() {
          try { writeHistory(); } catch (e) { result.historyProblem = problem(e); }
        });
      });
    }).then(done).catch(function(e) {
      result.status = -1;
      result.problem = problem(e);
      host.log('request failed: ' + result.problem);
      done();
    });
  }

  if (location.search === '?upload') {
    window.__online = {
      upload: function(payload) {
        try { upload(payload); } catch (e) { host.onPageError('sending online: ' + problem(e)); }
      }
    };
    host.onUploadReady();
  } else {
    fetchEverything();
  }
})();
