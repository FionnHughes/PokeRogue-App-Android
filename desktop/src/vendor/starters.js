// Turns a PokéRogue save into a plain-text list of the Pokémon usable as starters.
// Needs window.__saveTables (tables.js) for species, move and nature names.
(function() {
  var T = window.__saveTables;

  // Bits of a dex entry's caughtAttr.
  var SHINY = 2n;
  var SHINY_TIERS = [16n, 32n, 64n];

  function big(value) {
    try {
      return typeof value === 'string' ? BigInt(value) : BigInt(Math.trunc(Number(value) || 0));
    } catch (e) {
      return 0n;
    }
  }

  function moveNames(ids) {
    return ids.map(function(id) { return T.moves[id] || 'Move ' + id; }).join(', ');
  }

  // A starter is a base-form species; tables.eggMoves has one entry per starter.
  function starterIds(save) {
    var dex = save.dexData || {};
    return Object.keys(dex)
      .filter(function(id) { return T.eggMoves[id] && big(dex[id] && dex[id].caughtAttr) !== 0n; })
      .map(Number)
      .sort(function(a, b) { return a - b; });
  }

  function describe(save, id) {
    var dex = save.dexData[id] || {};
    var starter = (save.starterData || {})[id] || {};
    var caught = big(dex.caughtAttr);
    var lines = [(T.species[id] || 'Species ' + id) + ' (#' + id + ')'];

    var facts = [];
    if (Array.isArray(dex.ivs)) { facts.push('IVs ' + dex.ivs.join('/')); }
    var abilities = [];
    if (starter.abilityAttr & 1) { abilities.push('1'); }
    if (starter.abilityAttr & 2) { abilities.push('2'); }
    if (starter.abilityAttr & 4) { abilities.push('Hidden'); }
    if (abilities.length) { facts.push('Abilities ' + abilities.join(',')); }
    facts.push('Passive ' + (starter.passiveAttr & 1 ? 'unlocked' : 'locked'));
    facts.push('Candy ' + (starter.candyCount || 0));
    if (starter.valueReduction) { facts.push('Cost reduced x' + starter.valueReduction); }
    if (starter.classicWinCount) { facts.push('Classic wins ' + starter.classicWinCount); }
    lines.push('  ' + facts.join(' | '));

    var natures = [];
    for (var n = 0; n < T.natures.length; n++) {
      if (dex.natureAttr & (1 << (n + 1))) { natures.push(T.natures[n]); }
    }
    if (natures.length) {
      lines.push('  Natures: ' + (natures.length === T.natures.length ? 'all' : natures.join(', ')));
    }

    if (caught & SHINY) {
      var tiers = [];
      SHINY_TIERS.forEach(function(bit, index) { if (caught & bit) { tiers.push(index + 1); } });
      lines.push('  Shiny: tier ' + (tiers.join(', ') || '1'));
    }

    // The chosen starting moves: one list, or one list per form.
    var moveset = starter.moveset;
    if (Array.isArray(moveset) && moveset.length) {
      lines.push('  Moves: ' + moveNames(moveset));
    } else if (moveset && typeof moveset === 'object') {
      Object.keys(moveset).forEach(function(form) {
        if (Array.isArray(moveset[form]) && moveset[form].length) {
          lines.push('  Moves (form ' + form + '): ' + moveNames(moveset[form]));
        }
      });
    }

    var eggMoves = [];
    T.eggMoves[id].forEach(function(move, index) {
      if (starter.eggMoves & (1 << index)) {
        eggMoves.push((T.moves[move] || 'Move ' + move) + (index === 3 ? ' (rare)' : ''));
      }
    });
    if (eggMoves.length) { lines.push('  Egg moves: ' + eggMoves.join(', ')); }

    return lines.join('\n');
  }

  window.__starterTools = {
    /** How many starters the save has, or -1 if it cannot be read. */
    count: function(text) {
      try { return starterIds(JSON.parse(text)).length; } catch (e) { return -1; }
    },
    /** The full list as text. `label` says which save it is, e.g. "online save". */
    format: function(text, label) {
      var save = JSON.parse(text);
      var ids = starterIds(save);
      var saved = Number(save.timestamp) ? new Date(Number(save.timestamp)).toLocaleString() : 'unknown';
      var header = 'PokéRogue starters, ' + label + ' (last saved ' + saved + ')\n'
        + ids.length + ' Pokémon usable as starters\n'
        + 'Moves are the starting moves chosen in the starter screen; none listed means the defaults.';
      return header + '\n\n' + ids.map(function(id) { return describe(save, id); }).join('\n\n') + '\n';
    }
  };
})();
