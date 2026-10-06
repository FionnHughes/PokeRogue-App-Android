// Runs in the game's own page (online and offline). On a phone held upright the game
// is drawn from the very top of the screen, where rounded screen corners cover its
// top corners, including the wave number. This moves the game down by a small gap.
//
// Called with the gap the app suggests, in device pixels, from the screen's corner radius.
//
// Holding a finger on the gap steps through the gap sizes; the choice is kept.
// Sideways, the game fills the screen's height and nothing is changed.
(function(deviceGap) {
  if (window.__saveSyncTopGap || !document.body) { return; }
  window.__saveSyncTopGap = true;

  var KEY = 'saveSyncTopGap';
  var SIZES = [16, 24, 32, 40, 48];
  var HOLD_MS = 700;
  var ratio = window.devicePixelRatio || 1;
  var suggested = Math.max(SIZES[0], Math.min(SIZES[SIZES.length - 1], Math.round(deviceGap / ratio)));

  function chosen() {
    try {
      var kept = parseInt(localStorage.getItem(KEY), 10);
      return SIZES.indexOf(kept) >= 0 ? kept : 0;
    } catch (e) {
      return 0;
    }
  }

  var style = document.createElement('style');
  document.head.appendChild(style);
  // An empty strip over the gap. It shows nothing; it is only there to be held.
  var strip = document.createElement('div');
  strip.id = 'saveSyncTopGap';
  document.body.appendChild(strip);

  function apply() {
    var gap = chosen() || suggested;
    style.textContent =
      '#saveSyncTopGap { display: none; position: fixed; top: 0; left: 0; right: 0; height: ' + gap + 'px;'
      + ' z-index: 10; user-select: none; -webkit-user-select: none; touch-action: none; }\n'
      + '@media (orientation: portrait) {\n'
      // The game sizes itself to its container, which fills the body: with the padding
      // inside the body's height, the container starts lower and ends at the same place.
      + '  body { padding-top: ' + gap + 'px !important; box-sizing: border-box !important; }\n'
      + '  #saveSyncTopGap { display: block; }\n'
      + '}';
    // The game measures its container again when the window reports a new size.
    window.dispatchEvent(new Event('resize'));
  }

  var hold = null;
  function release() { clearTimeout(hold); hold = null; }
  strip.addEventListener('pointerdown', function() {
    release();
    hold = setTimeout(function() {
      var next = SIZES[(SIZES.indexOf(chosen() || suggested) + 1) % SIZES.length];
      try { localStorage.setItem(KEY, String(next)); } catch (e) {}
      apply();
    }, HOLD_MS);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(function(name) { strip.addEventListener(name, release); });

  apply();
})
