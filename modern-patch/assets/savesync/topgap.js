// Runs in the game's own page (online and offline). On a phone held upright the game
// is drawn from the very top of the screen, where rounded screen corners cover its
// top corners, including the wave number. This moves the game down by a small gap
// and shows the time in that gap, in the game's own pixel font.
//
// Called with:
//   deviceGap  the gap the app suggests, in device pixels, from the screen's corner radius
//   cutouts    camera cutouts as [left, top, right, bottom] in device pixels
//
// Holding a finger on the clock steps through the gap sizes; the choice is kept.
// Sideways, the game fills the screen's height and nothing is changed.
(function(deviceGap, cutouts) {
  if (window.__saveSyncTopGap || !document.body) { return; }
  window.__saveSyncTopGap = true;

  var KEY = 'saveSyncTopGap';
  var SIZES = [16, 24, 32, 40, 48];
  var CLOCK_WIDTH = 96;
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
  var clock = document.createElement('div');
  clock.id = 'saveSyncClock';
  document.body.appendChild(clock);

  // The clock sits in the middle unless a camera cutout is there; then a quarter in from a side.
  function clockCentre(gap) {
    var width = document.documentElement.clientWidth;
    var places = [0.5, 0.25, 0.75];
    for (var i = 0; i < places.length; i++) {
      var left = width * places[i] - CLOCK_WIDTH / 2;
      var clear = (cutouts || []).every(function(c) {
        return c[2] / ratio <= left || c[0] / ratio >= left + CLOCK_WIDTH || c[1] / ratio >= gap || c[3] / ratio <= 0;
      });
      if (clear) { return places[i]; }
    }
    return places[0];
  }

  function apply() {
    var gap = chosen() || suggested;
    style.textContent =
      '#saveSyncClock { display: none; position: fixed; top: 0; left: ' + clockCentre(gap) * 100 + '%;'
      + ' width: ' + CLOCK_WIDTH + 'px; margin-left: -' + CLOCK_WIDTH / 2 + 'px; height: ' + gap + 'px;'
      + ' line-height: ' + gap + 'px; font: ' + Math.min(gap, 32) + 'px/' + gap + 'px emerald, monospace;'
      + ' text-align: center; color: #f8f8f8; text-shadow: 1px 1px 0 #6b5a73; z-index: 10;'
      + ' user-select: none; -webkit-user-select: none; touch-action: none; }\n'
      + '@media (orientation: portrait) {\n'
      // The game sizes itself to its container, which fills the body: with the padding
      // inside the body's height, the container starts lower and ends at the same place.
      + '  body { padding-top: ' + gap + 'px !important; box-sizing: border-box !important; }\n'
      + '  #saveSyncClock { display: block; }\n'
      + '}';
    // The game measures its container again when the window reports a new size.
    window.dispatchEvent(new Event('resize'));
  }

  function tick() {
    var now = new Date();
    var text = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2);
    if (clock.textContent !== text) { clock.textContent = text; }
  }

  var hold = null;
  function release() { clearTimeout(hold); hold = null; }
  clock.addEventListener('pointerdown', function() {
    release();
    hold = setTimeout(function() {
      var next = SIZES[(SIZES.indexOf(chosen() || suggested) + 1) % SIZES.length];
      try { localStorage.setItem(KEY, String(next)); } catch (e) {}
      apply();
    }, HOLD_MS);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(function(name) { clock.addEventListener(name, release); });

  apply();
  tick();
  setInterval(tick, 5000);
})
