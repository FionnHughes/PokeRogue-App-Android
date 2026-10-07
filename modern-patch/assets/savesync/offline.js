// Helper page on the offline game's origin (https://localhost:8080), so it shares that
// game's localStorage. It describes both sides, works out the recommended sync, and
// does every read and write on the offline side.
//
// The offline game keeps everything as btoa(encodeURIComponent(json)):
//   data_Guest                  save data
//   runHistoryData_Guest        run history
//   sessionData_Guest           run in progress, slot 1
//   sessionData1_Guest ... 4    runs in progress, slots 2 to 5
//
// A "snapshot" is { save, history, sessions[5] }, each a JSON text or '' for none.
// A "plan" says where each part goes: 'down' (online to offline), 'up' (offline to
// online), 'both' (run history only: merge into both), or '' (leave alone). A slot can
// also be 'endDown' or 'endUp': remove the run from offline or from online, because
// it has already ended on the other side.
(function() {
  var SLOTS = 5;
  var HISTORY_LIMIT = 25; // the game's own cap on stored runs
  var host = window.saveSyncHost;

  window.onerror = function(message, source, line) {
    try { host.onPageError('offline page: ' + message + ' (line ' + line + ')'); } catch (e) {}
  };

  function saveKey() { return 'data_Guest'; }
  function historyKey() { return 'runHistoryData_Guest'; }
  function sessionKey(slot) { return 'sessionData' + (slot || '') + '_Guest'; }

  function read(key) {
    try {
      var value = localStorage.getItem(key);
      return value ? decodeURIComponent(atob(value)) : '';
    } catch (e) {
      return '';
    }
  }
  function store(key, text) { localStorage.setItem(key, btoa(encodeURIComponent(text))); }
  function parse(text) {
    try { var value = text ? JSON.parse(text) : null; return value && typeof value === 'object' ? value : null; }
    catch (e) { return null; }
  }
  function emptySnapshot() { return { save: '', history: '', sessions: ['', '', '', '', ''] }; }
  function normalize(snapshot) {
    var out = emptySnapshot();
    if (!snapshot) { return out; }
    out.save = typeof snapshot.save === 'string' ? snapshot.save : '';
    out.history = typeof snapshot.history === 'string' ? snapshot.history : '';
    for (var i = 0; i < SLOTS; i++) {
      var s = snapshot.sessions && snapshot.sessions[i];
      out.sessions[i] = typeof s === 'string' ? s : '';
    }
    return out;
  }
  function localSnapshot() {
    var out = emptySnapshot();
    out.save = read(saveKey());
    out.history = read(historyKey());
    for (var i = 0; i < SLOTS; i++) { out.sessions[i] = read(sessionKey(i)); }
    return out;
  }

  // ---- Describing the parts ----

  function summarizeSave(text) {
    if (!text) { return { exists: false }; }
    var d = parse(text);
    if (!d) { return { exists: true, broken: true }; }
    var dex = d.dexData || {};
    var caught = 0;
    for (var k in dex) {
      var attr = dex[k] && dex[k].caughtAttr;
      if (attr && String(attr) !== '0') { caught++; }
    }
    return {
      exists: true,
      timestamp: Number(d.timestamp) || 0,
      caught: caught,
      playTime: Number(d.gameStats && d.gameStats.playTime) || 0,
      profile: String(d.trainerId) + '/' + String(d.secretId),
      starters: window.__starterTools ? window.__starterTools.count(text) : -1
    };
  }
  // A run history is an object of finished runs keyed by the time each ended.
  function runs(text) {
    var value = parse(text);
    return value && !Array.isArray(value) ? value : {};
  }
  function summarizeHistory(text, otherText) {
    var mine = runs(text);
    var other = runs(otherText);
    var keys = Object.keys(mine);
    return { runs: keys.length, notInOther: keys.filter(function(k) { return !(k in other); }).length };
  }
  var MODES = ['Classic', 'Endless', 'Spliced Endless', 'Daily Run', 'Challenge']; // the game's GameModes
  function summarizeSession(text) {
    var d = parse(text);
    if (!d) { return null; }
    return {
      timestamp: Number(d.timestamp) || 0,
      wave: Number(d.waveIndex) || 0,
      seed: String(d.seed || ''),
      party: Array.isArray(d.party) ? d.party.length : 0,
      mode: MODES[d.gameMode] || 'Run'
    };
  }
  // Whether a run in progress has already ended on the other side: that side's run
  // history then holds the same run (same seed) at the same wave or later. Answers
  // { wave, victory } or null. A run that ended is over, wherever a copy of it remains.
  function endedIn(session, otherHistoryText) {
    if (!session || !session.seed) { return null; }
    var finished = runs(otherHistoryText);
    for (var k in finished) {
      var entry = finished[k] && finished[k].entry;
      if (entry && String(entry.seed || '') === session.seed && (Number(entry.waveIndex) || 0) >= session.wave) {
        return { wave: Number(entry.waveIndex) || 0, victory: !!finished[k].isVictory };
      }
    }
    return null;
  }
  // Whether a run that only offline has may already be over online. The online side
  // has been saved after this run was last saved offline, so online was played since,
  // and the likeliest reason the run is missing there is that it ended or was deleted
  // there, on this phone or on another device. Run history cannot always tell: it is
  // kept per device and never reaches the server. Such a run is not sent online unasked.
  function mayHaveEndedOnline(mine, theirs, onlineSave) {
    return !!mine && !theirs && !!onlineSave.exists && !onlineSave.broken && mine.timestamp <= onlineSave.timestamp;
  }
  // Adds the source's runs to the target's and keeps the newest ones.
  function mergeHistory(sourceText, targetText) {
    var merged = runs(targetText);
    var source = runs(sourceText);
    Object.keys(source).forEach(function(k) { merged[k] = source[k]; });
    var kept = {};
    Object.keys(merged)
      .sort(function(a, b) { return Number(b) - Number(a); })
      .slice(0, HISTORY_LIMIT)
      .forEach(function(k) { kept[k] = merged[k]; });
    return JSON.stringify(kept);
  }

  // ---- The recommended sync ----

  // Works out, part by part, which side is ahead. Flags: `canUpload` is false when the
  // online side cannot be written (not logged in), `knowsHistory` is true when the
  // online run history could be read, and `sessionsUnknown` is true when the server
  // did not answer for every slot, so an "empty" online slot may not be empty.
  function recommend(local, online, flags) {
    var plan = { save: '', history: '', sessions: ['', '', '', '', ''] };
    var lines = [];
    var a = summarizeSave(local.save);
    var b = summarizeSave(online.save);
    var localOk = a.exists && !a.broken;
    var onlineOk = b.exists && !b.broken;
    // An offline save that did not start from this account's save cannot go online
    // (the server refuses it), and its runs in progress belong to that other save.
    var otherProfile = localOk && onlineOk && a.profile !== b.profile;

    if (otherProfile) {
      lines.push('Save data: left alone. The offline save did not come from this account, so use the manual copies for it.');
    } else if (onlineOk && !localOk) {
      plan.save = 'down';
      lines.push('Save data: online to offline (there is no offline save)');
    } else if (localOk && onlineOk) {
      if (b.playTime !== a.playTime) {
        plan.save = b.playTime > a.playTime ? 'down' : 'up';
        lines.push('Save data: ' + direction(plan.save) + ' (' + sideOf(plan.save) + ' has more play time)');
      } else if (b.timestamp !== a.timestamp) {
        plan.save = b.timestamp > a.timestamp ? 'down' : 'up';
        lines.push('Save data: ' + direction(plan.save) + ' (' + sideOf(plan.save) + ' is newer)');
      } else {
        lines.push('Save data: already the same');
      }
    }
    if (plan.save === 'up' && !flags.canUpload) { plan.save = ''; lines.pop(); }

    if (flags.knowsHistory) {
      var toOffline = summarizeHistory(online.history, local.history).notInOther;
      var toOnline = summarizeHistory(local.history, online.history).notInOther;
      if (toOffline || toOnline) {
        plan.history = toOffline && toOnline ? 'both' : toOffline ? 'down' : 'up';
        lines.push('Run history: merge (' + toOffline + ' to offline, ' + toOnline + ' to online)');
      } else if (Object.keys(runs(local.history)).length) {
        lines.push('Run history: already the same');
      }
    }

    if (flags.sessionsUnknown) {
      lines.push('Runs in progress: left alone, because the server did not answer for every slot.');
    } else if (!otherProfile) {
      for (var i = 0; i < SLOTS; i++) {
        var mine = summarizeSession(local.sessions[i]);
        var theirs = summarizeSession(online.sessions[i]);
        var pick = '';
        var why = '';
        // A copy of a run that has ended on the other side is not a run in progress any
        // more. It is removed, or replaced if the other side has a live run in that slot.
        var mineOver = endedIn(mine, online.history);
        var theirsOver = endedIn(theirs, local.history);
        if (mineOver || theirsOver) {
          if (mineOver) { pick = theirs && !theirsOver ? 'down' : 'endDown'; }
          else { pick = mine ? 'up' : 'endUp'; }
          if ((pick === 'up' || pick === 'endUp') && !flags.canUpload) { continue; }
          plan.sessions[i] = pick;
          var over = mineOver || theirsOver;
          lines.push('Run in progress, slot ' + (i + 1) + ': '
            + (pick === 'endDown' ? 'remove from offline' : pick === 'endUp' ? 'remove from online' : direction(pick))
            + ' (' + (mineOver ? 'the offline copy is of a run that ended online' : 'the online copy is of a run that ended offline')
            + ' at wave ' + over.wave + ')');
          continue;
        }
        if (mayHaveEndedOnline(mine, theirs, b)) {
          lines.push('Run in progress, slot ' + (i + 1) + ': left alone. Online was played after this run was last'
            + ' saved here, so it may already have ended there.');
          continue;
        }
        if (mine && !theirs) { pick = 'up'; why = 'only offline has one, and it was played after online was last saved'; }
        else if (theirs && !mine) { pick = 'down'; why = 'only online has one'; }
        else if (mine && theirs) {
          if (mine.seed === theirs.seed && mine.wave !== theirs.wave) {
            pick = theirs.wave > mine.wave ? 'down' : 'up';
            why = 'wave ' + Math.max(mine.wave, theirs.wave) + ' against ' + Math.min(mine.wave, theirs.wave);
          } else if (mine.timestamp !== theirs.timestamp) {
            pick = theirs.timestamp > mine.timestamp ? 'down' : 'up';
            why = sideOf(pick) + ' is newer';
          }
        }
        if (pick === 'up' && !flags.canUpload) { pick = ''; }
        if (pick) {
          plan.sessions[i] = pick;
          lines.push('Run in progress, slot ' + (i + 1) + ': ' + direction(pick) + ' (' + why + ')');
        }
      }
    }
    return { plan: plan, lines: lines };
  }
  function direction(d) { return d === 'down' ? 'online to offline' : 'offline to online'; }
  function sideOf(d) { return d === 'down' ? 'online' : 'offline'; }

  // ---- Backups ----

  // Keeps what is about to be replaced on the offline side. Answers false if it could
  // not be stored, in which case nothing may be replaced. Nothing to keep is a success.
  function backUp(reason, parts) {
    var kept = normalize(parts);
    var runCount = Object.keys(runs(kept.history)).length;
    if (!runCount) { kept.history = ''; }
    if (!parse(kept.save)) { kept.save = ''; }
    var sessionCount = kept.sessions.filter(function(s) { return !!s; }).length;
    if (!kept.save && !kept.history && !sessionCount) { return true; }
    var meta = JSON.stringify({
      created: Date.now(), origin: 'offline', reason: reason,
      save: kept.save ? summarizeSave(kept.save) : null,
      runs: runCount,
      sessions: kept.sessions.map(summarizeSession)
    });
    return host.backup(meta, kept.save, kept.history, sessionCount ? JSON.stringify(kept.sessions) : '') === true;
  }

  // ---- What Java calls ----

  var online = emptySnapshot();

  window.__sync = {
    // onlineSnapshot: what the online page fetched. flags: { canUpload, knowsHistory }.
    compare: function(onlineSnapshot, flags) {
      try {
        online = normalize(onlineSnapshot);
        var local = localSnapshot();
        var recommended = recommend(local, online, flags || {});
        // Each run in progress also says whether it has ended on the other side.
        var describe = function(texts, otherHistory) {
          return texts.map(function(text) {
            var session = summarizeSession(text);
            if (session) { session.ended = endedIn(session, otherHistory); }
            return session;
          });
        };
        var onlineSave = summarizeSave(online.save);
        var mine = describe(local.sessions, online.history);
        mine.forEach(function(session, slot) {
          if (session && !session.ended) {
            session.maybeEnded = mayHaveEndedOnline(session, summarizeSession(online.sessions[slot]), onlineSave);
          }
        });
        host.onCompared(JSON.stringify({
          local: summarizeSave(local.save),
          online: onlineSave,
          localHistory: summarizeHistory(local.history, online.history),
          onlineHistory: summarizeHistory(online.history, local.history),
          localSessions: mine,
          onlineSessions: describe(online.sessions, local.history),
          recommended: recommended.plan,
          recommendedLines: recommended.lines
        }));
      } catch (e) {
        host.onPageError('comparing the saves: ' + String((e && e.message) || e));
      }
    },

    // Carries out a plan. The offline side is backed up and written here; whatever
    // has to go online is handed back for the online page to send.
    execute: function(plan) {
      try {
        var local = localSnapshot();
        var replaced = emptySnapshot();
        var upload = emptySnapshot();
        upload.remove = [false, false, false, false, false]; // online slots to empty
        var mergedHistory = plan.history ? mergeHistory(
          plan.history === 'up' ? local.history : online.history,
          plan.history === 'up' ? online.history : local.history) : '';

        if (plan.save === 'down') {
          if (!parse(online.save)) { throw new Error('there is no online save to copy'); }
          replaced.save = local.save;
        } else if (plan.save === 'up') {
          if (!parse(local.save)) { throw new Error('there is no offline save to copy'); }
          upload.save = local.save;
        }
        if (plan.history === 'down' || plan.history === 'both') { replaced.history = local.history; }
        if (plan.history === 'up' || plan.history === 'both') { upload.history = mergedHistory; }
        for (var i = 0; i < SLOTS; i++) {
          var move = plan.sessions && plan.sessions[i];
          if (move === 'down') {
            if (!parse(online.sessions[i])) { throw new Error('online slot ' + (i + 1) + ' is empty'); }
            replaced.sessions[i] = local.sessions[i];
          } else if (move === 'up') {
            if (!parse(local.sessions[i])) { throw new Error('offline slot ' + (i + 1) + ' is empty'); }
            upload.sessions[i] = local.sessions[i];
          } else if (move === 'endDown') {
            replaced.sessions[i] = local.sessions[i];
          } else if (move === 'endUp') {
            upload.remove[i] = true;
          }
        }

        if (!backUp('before a sync', replaced)) { throw new Error('a backup could not be stored first'); }

        if (plan.save === 'down') { store(saveKey(), online.save); }
        if (plan.history === 'down' || plan.history === 'both') { store(historyKey(), mergedHistory); }
        for (var j = 0; j < SLOTS; j++) {
          if (plan.sessions && plan.sessions[j] === 'down') { store(sessionKey(j), online.sessions[j]); }
          if (plan.sessions && plan.sessions[j] === 'endDown') { localStorage.removeItem(sessionKey(j)); }
        }

        var hasUpload = upload.save || upload.history || upload.sessions.some(function(s) { return !!s; })
          || upload.remove.some(function(r) { return r; });
        host.onExecuted(true, '', hasUpload ? JSON.stringify(upload) : '');
      } catch (e) {
        host.onExecuted(false, String((e && e.message) || e), '');
      }
    },

    // Puts a backup back exactly as it was. Parts the backup lacks are left alone.
    restore: function(snapshot) {
      try {
        var backup = normalize(snapshot);
        var local = localSnapshot();
        var replaced = emptySnapshot();
        var any = false;
        if (backup.save) {
          if (!parse(backup.save)) { throw new Error('the backup\'s save data cannot be read'); }
          replaced.save = local.save; any = true;
        }
        if (backup.history) {
          if (!parse(backup.history)) { throw new Error('the backup\'s run history cannot be read'); }
          replaced.history = local.history; any = true;
        }
        for (var i = 0; i < SLOTS; i++) {
          if (backup.sessions[i]) {
            if (!parse(backup.sessions[i])) { throw new Error('the backup\'s slot ' + (i + 1) + ' cannot be read'); }
            replaced.sessions[i] = local.sessions[i]; any = true;
          }
        }
        if (!any) { throw new Error('the backup is empty'); }
        if (!backUp('before restoring a backup', replaced)) { throw new Error('a backup could not be stored first'); }
        if (backup.save) { store(saveKey(), backup.save); }
        if (backup.history) { store(historyKey(), backup.history); }
        for (var j = 0; j < SLOTS; j++) {
          if (backup.sessions[j]) { store(sessionKey(j), backup.sessions[j]); }
        }
        host.onExecuted(true, '', '');
      } catch (e) {
        host.onExecuted(false, String((e && e.message) || e), '');
      }
    },

    starters: function(which) {
      try {
        if (!window.__starterTools) { throw new Error('the name tables did not load'); }
        var text = which === 'online' ? online.save : read(saveKey());
        if (!text) { throw new Error('there is no save to read'); }
        var list = window.__starterTools.format(text, which === 'online' ? 'online save' : 'offline save');
        host.onStarters(window.__starterTools.count(text), list);
      } catch (e) {
        host.onStarters(-1, String((e && e.message) || e));
      }
    }
  };

  host.log('offline page ready');
  host.onReady();
})();
