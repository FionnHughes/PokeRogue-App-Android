// Turns a PokéRogue run in progress (session save data) into plain text: the party
// with levels, natures, moves and held items, and the run's other items.
// Needs window.__saveTables (tables.js). Used by the phone app's "Copy current team"
// and, as a copy, by the desktop app (desktop/src/vendor/team.js).
(function() {
  var T = window.__saveTables;
  var STATS = ['HP', 'Attack', 'Defense', 'Sp. Atk', 'Sp. Def', 'Speed'];
  var MODES = ['Classic', 'Endless', 'Spliced Endless', 'Daily Run', 'Challenge'];

  function pretty(id) {
    return String(id || '').toLowerCase().split('_').map(function(w) {
      return w ? w.charAt(0).toUpperCase() + w.slice(1) : w;
    }).join(' ');
  }
  function name(table, id, fallback) {
    var known = T && T[table] && T[table][id];
    return known || (fallback || '#') + id;
  }
  function nickname(text) {
    if (!text) { return ''; }
    try { return decodeURIComponent(escape(atob(text))); } catch (e) { return ''; }
  }
  function duration(seconds) {
    var minutes = Math.round((Number(seconds) || 0) / 60);
    return Math.floor(minutes / 60) + ' h ' + (minutes % 60) + ' min';
  }

  // A modifier's name, with what its pregenerated argument picks (berry, type, stat).
  function itemName(m) {
    var arg = Array.isArray(m.typePregenArgs) ? m.typePregenArgs[0] : undefined;
    if (m.typeId === 'BERRY' && arg !== undefined) { return name('berries', arg, 'Berry ') + ' Berry'; }
    if (m.typeId === 'ATTACK_TYPE_BOOSTER' && arg !== undefined) { return name('types', arg, 'Type ') + ' type booster'; }
    if (m.typeId === 'BASE_STAT_BOOSTER' && arg !== undefined) { return (STATS[arg] || 'Stat ' + arg) + ' booster'; }
    if (m.typeId === 'MINT' && arg !== undefined) { return (T.natures[arg] || 'Nature ' + arg) + ' Mint'; }
    return pretty(m.typeId || m.className || 'item');
  }
  function withCount(m) {
    var count = Number(m.stackCount) || 1;
    return itemName(m) + (count > 1 ? ' x' + count : '');
  }

  function member(p, held) {
    var species = p.species === undefined ? 'Unknown Pokémon' : name('species', p.species, 'Species ');
    if (Number(p.fusionSpecies) > 0) { species += '/' + name('species', p.fusionSpecies, 'Species '); }
    var nick = nickname(p.nickname);
    var head = species + (nick && nick !== species ? ' "' + nick + '"' : '')
      + (p.shiny ? ' ★' + (Number(p.variant) > 0 ? ' tier ' + (Number(p.variant) + 1) : '') : '')
      + (p.species === undefined ? '' : ' (#' + p.species + ')') + ', Lv ' + (p.level || '?');
    var lines = [head];
    lines.push('  Item' + (held.length === 1 ? '' : 's') + ': ' + (held.length ? held.map(withCount).join(', ') : 'none'));
    var facts = [];
    if (p.nature !== undefined && T.natures[p.nature]) { facts.push(T.natures[p.nature]); }
    facts.push('Ability: ' + (p.abilityIndex === 2 ? 'hidden' : 'slot ' + ((Number(p.abilityIndex) || 0) + 1)));
    if (p.passive) { facts.push('Passive on'); }
    if (p.teraType !== undefined && p.teraType !== null && Number(p.teraType) >= 0) { facts.push('Tera ' + name('types', p.teraType, 'Type ')); }
    lines.push('  ' + facts.join(' | '));
    var stats = [];
    if (Array.isArray(p.stats) && p.stats.length) { stats.push('HP ' + (p.hp === undefined ? '?' : p.hp) + '/' + p.stats[0]); }
    if (Array.isArray(p.ivs)) { stats.push('IVs ' + p.ivs.join('/')); }
    if (stats.length) { lines.push('  ' + stats.join(' | ')); }
    var moves = (p.moveset || []).filter(Boolean).map(function(m) { return name('moves', m.moveId, 'Move '); });
    if (moves.length) { lines.push('  Moves: ' + moves.join(', ')); }
    return lines.join('\n');
  }

  window.__teamTools = {
    /** One line about a run, for choosing between slots. */
    summary: function(text) {
      var d = JSON.parse(text);
      var party = (d.party || []).map(function(p) { return name('species', p.species, 'Species ') + ' Lv ' + p.level; });
      return (MODES[d.gameMode] || 'Run') + ', wave ' + d.waveIndex + (party.length ? ': ' + party.join(', ') : '');
    },
    /** The whole team as text. `label` says where the run is, e.g. "offline slot 1". */
    format: function(text, label) {
      var d = JSON.parse(text);
      var party = Array.isArray(d.party) ? d.party : [];
      var ids = party.map(function(p) { return p.id; });
      var held = party.map(function() { return []; });
      var other = [];
      (d.modifiers || []).forEach(function(m) {
        var owner = Array.isArray(m.args) ? ids.indexOf(m.args[0]) : -1;
        if (owner >= 0 && m.args[0] !== undefined && m.args[0] !== null) { held[owner].push(m); } else { other.push(m); }
      });
      var biome = d.arena && d.arena.biome !== undefined ? ' in ' + name('biomes', d.arena.biome, 'Biome ') : '';
      var saved = Number(d.timestamp) ? new Date(Number(d.timestamp)).toLocaleString() : 'unknown';
      var lines = [
        'PokéRogue current team, ' + label,
        (MODES[d.gameMode] || 'Run') + ', wave ' + d.waveIndex + biome + ', saved ' + saved
          + ', run time ' + duration(d.playTime) + ', money ' + (Number(d.money) || 0).toLocaleString('en-GB'),
        ''
      ];
      party.forEach(function(p, i) { lines.push(member(p, held[i]), ''); });
      lines.push('Other items: ' + (other.length ? other.map(withCount).join(', ') : 'none'));
      return lines.join('\n') + '\n';
    }
  };
})();
