// Runs in the game's own page (online and offline). It decides where on the screen
// the game is drawn, separately for the phone held upright and held sideways.
//
// A layout is a box for each of the two, kept as how far each edge sits in from the
// screen's edge, as a share of the screen: { portrait: { t, l, r, b }, landscape: ... }.
// The game keeps its shape and is drawn as large as fits inside the box. The game's
// own touch controls are fixed to the screen, so the box does not move them.
//
// Until a box is chosen, the upright game starts a little below the top, clear of
// rounded screen corners, and the sideways game uses the whole screen.
//
// Called with:
//   deviceGap  the gap the app suggests for the top, in device pixels
//   saved      the layout the app has stored, or null
//
// window.__saveSyncLayout.edit() opens the editor: a box to drag and resize on top of
// the running game and its controls.
(function(deviceGap, saved) {
  if (window.__saveSyncLayout || !document.body) { return; }

  var KEY = 'saveSyncLayout';
  var SIDES = ['t', 'l', 'r', 'b'];
  var MIN_WIDTH = 0.3; // the smallest box, as shares of the screen
  var MIN_HEIGHT = 0.3;
  var LOOKS_MS = [250, 1300]; // when to check the game's size after the phone is turned
  var ratio = window.devicePixelRatio || 1;

  function clamp(value, low, high) { return Math.max(low, Math.min(high, value)); }
  function copy(box) { return { t: box.t, l: box.l, r: box.r, b: box.b }; }
  function isBox(box) {
    return !!box && SIDES.every(function(side) { return typeof box[side] === 'number' && box[side] >= 0 && box[side] < 1; })
      && box.l + box.r <= 1 - MIN_WIDTH + 1e-6 && box.t + box.b <= 1 - MIN_HEIGHT + 1e-6;
  }
  // Keeps only what is a usable box; anything else falls back to the default.
  function read(value) {
    try {
      var parsed = typeof value === 'string' ? JSON.parse(value) : value;
      var out = {};
      ['portrait', 'landscape'].forEach(function(which) {
        if (parsed && isBox(parsed[which])) { out[which] = copy(parsed[which]); }
      });
      return out;
    } catch (e) {
      return {};
    }
  }
  function stored(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

  function orientation() { return window.matchMedia('(orientation: portrait)').matches ? 'portrait' : 'landscape'; }
  function defaults(which) {
    if (which !== 'portrait') { return { t: 0, l: 0, r: 0, b: 0 }; }
    // An earlier build let the gap's size be chosen; that choice still counts.
    var gap = parseInt(stored('saveSyncTopGap'), 10) || Math.round(deviceGap / ratio);
    return { t: clamp(gap, 16, 48) / Math.max(window.innerWidth, window.innerHeight, 1), l: 0, r: 0, b: 0 };
  }

  var fromApp = read(saved);
  var layout = fromApp.portrait || fromApp.landscape ? fromApp : read(stored(KEY));
  function boxOf(source, which) { return source[which] || defaults(which); }

  // ---- Putting the game in its box ----

  var style = document.createElement('style');
  document.head.appendChild(style);
  var nudge = 0;
  var showing = layout;

  function share(value, unit) { return (value * 100).toFixed(3) + unit; }
  function rule(which, box) {
    // The game sizes itself to its container, which fills the body: with the padding
    // inside the body's size, the container becomes the box.
    return '@media (orientation: ' + which + ') {\n  body { padding: ' + share(box.t, 'vh') + ' ' + share(box.r, 'vw')
      + ' calc(' + share(box.b, 'vh') + ' + ' + nudge + 'px) ' + share(box.l, 'vw')
      + ' !important; box-sizing: border-box !important; }\n}\n';
  }
  function apply(source) {
    showing = source;
    // The app can scale the game's container to fill the screen, which would push the
    // game out of its box. The box decides the size here.
    style.textContent = '#app { transform: none !important; }\n'
      + rule('portrait', boxOf(source, 'portrait')) + rule('landscape', boxOf(source, 'landscape'));
  }

  // The game re-measures its container when the window reports a new size.
  var announcing = false;
  function announce() {
    announcing = true;
    try { window.dispatchEvent(new Event('resize')); } finally { announcing = false; }
  }

  // After the phone is turned, the game can keep the size it had before: it resizes
  // itself with the old measurement, takes the new one afterwards, and then sees no
  // reason to resize again. Its size is checked against the box here, and if it is
  // off, the box is made one pixel shorter for a moment, which the game does notice.
  function isOff() {
    var app = document.getElementById('app');
    var canvas = app && app.querySelector('canvas');
    if (!canvas || !canvas.width || !canvas.height) { return false; }
    var space = app.getBoundingClientRect();
    var fits = Math.min(space.width, space.height * canvas.width / canvas.height);
    return fits > 0 && Math.abs(canvas.getBoundingClientRect().width - fits) > 2;
  }
  var looks = [];
  function refit() {
    if (!isOff()) { return; }
    nudge = 1;
    apply(showing);
    announce();
    looks.push(setTimeout(function() {
      nudge = 0;
      apply(showing);
      announce();
    }, 120));
  }
  function check() {
    looks.forEach(clearTimeout);
    if (nudge) { nudge = 0; apply(showing); }
    looks = LOOKS_MS.map(function(delay) { return setTimeout(refit, delay); });
  }
  function turned() {
    if (announcing) { return; }
    if (editor) { editor.show(); }
    check();
  }
  window.addEventListener('resize', turned);
  if (screen.orientation && screen.orientation.addEventListener) { screen.orientation.addEventListener('change', turned); }

  // ---- The editor ----

  var editor = null;

  function element(tag, css, text) {
    var made = document.createElement(tag);
    made.style.cssText = css;
    if (text) { made.textContent = text; }
    return made;
  }

  function edit() {
    if (editor) { return; }
    var draft = { portrait: copy(boxOf(layout, 'portrait')), landscape: copy(boxOf(layout, 'landscape')) };

    // Covers the game and its controls, which stay visible underneath but get no touches.
    var root = element('div', 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483646;touch-action:none;'
      + 'background:rgba(0,0,0,.3);font:14px/1.35 sans-serif;color:#fff;user-select:none;-webkit-user-select:none;');
    var frame = element('div', 'position:absolute;box-sizing:border-box;border:2px dashed #fff;'
      + 'background:rgba(255,255,255,.06);touch-action:none;');
    root.appendChild(frame);

    function drag(target, mode) {
      target.addEventListener('pointerdown', function(down) {
        down.preventDefault();
        down.stopPropagation();
        var which = orientation();
        var from = copy(draft[which]);
        var width = window.innerWidth;
        var height = window.innerHeight;
        function move(event) {
          var dx = (event.clientX - down.clientX) / width;
          var dy = (event.clientY - down.clientY) / height;
          var box = copy(from);
          if (mode === 'move') {
            dx = clamp(dx, -from.l, from.r);
            dy = clamp(dy, -from.t, from.b);
            box.l += dx; box.r -= dx; box.t += dy; box.b -= dy;
          } else {
            if (mode.indexOf('l') >= 0) { box.l = clamp(from.l + dx, 0, 1 - from.r - MIN_WIDTH); }
            if (mode.indexOf('r') >= 0) { box.r = clamp(from.r - dx, 0, 1 - from.l - MIN_WIDTH); }
            if (mode.indexOf('t') >= 0) { box.t = clamp(from.t + dy, 0, 1 - from.b - MIN_HEIGHT); }
            if (mode.indexOf('b') >= 0) { box.b = clamp(from.b - dy, 0, 1 - from.t - MIN_HEIGHT); }
          }
          draft[which] = box;
          show();
        }
        function done() {
          target.removeEventListener('pointermove', move);
          target.removeEventListener('pointerup', done);
          target.removeEventListener('pointercancel', done);
        }
        try { target.setPointerCapture(down.pointerId); } catch (e) {}
        target.addEventListener('pointermove', move);
        target.addEventListener('pointerup', done);
        target.addEventListener('pointercancel', done);
      });
    }
    drag(frame, 'move');
    // One handle per corner: a square to see, inside a larger area to hit. They sit
    // inside the box, so they can be reached when the box is at the screen's edge,
    // away from rounded corners and from the strips where the phone reads swipes.
    ['tl', 'tr', 'bl', 'br'].forEach(function(corner) {
      var vertical = corner.charAt(0) === 't' ? 'top' : 'bottom';
      var horizontal = corner.charAt(1) === 'l' ? 'left' : 'right';
      var handle = element('div', 'position:absolute;width:60px;height:60px;touch-action:none;'
        + vertical + ':0;' + horizontal + ':0;');
      handle.appendChild(element('div', 'position:absolute;width:22px;height:22px;box-sizing:border-box;'
        + 'background:#fff;border:2px solid #222;border-radius:4px;' + vertical + ':20px;' + horizontal + ':20px;'));
      drag(handle, corner);
      frame.appendChild(handle);
    });

    var panel = element('div', 'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);'
      + 'width:280px;max-width:80vw;box-sizing:border-box;padding:10px 12px;text-align:center;'
      + 'background:rgba(20,20,28,.92);border:1px solid #999;border-radius:8px;touch-action:none;');
    var title = element('div', 'font-weight:bold;margin-bottom:4px;');
    panel.appendChild(title);
    panel.appendChild(element('div', 'font-size:12px;margin-bottom:8px;',
      'Drag a white square to resize the box, or drag inside the box to move it. The game keeps its shape and fits inside.'
      + ' Turn the phone to set the other layout.'));
    function button(label, action) {
      var made = element('button', 'font:14px sans-serif;margin:0 4px;padding:8px 12px;border-radius:6px;'
        + 'border:1px solid #999;background:#2d2d3a;color:#fff;', label);
      made.addEventListener('click', action);
      // A press on a button must not start moving the box underneath.
      made.addEventListener('pointerdown', function(event) { event.stopPropagation(); });
      panel.appendChild(made);
    }
    button('Save', function() {
      layout = { portrait: draft.portrait, landscape: draft.landscape };
      var text = JSON.stringify(layout);
      try { localStorage.setItem(KEY, text); } catch (e) {}
      // The app keeps it too, so the online and the offline game share one layout.
      try { window.saveSyncGame.saveLayout(text); } catch (e) {}
      close();
    });
    button('Reset', function() { draft[orientation()] = defaults(orientation()); show(); });
    button('Cancel', close);
    panel.addEventListener('pointerdown', function(event) { event.stopPropagation(); });
    root.appendChild(panel);

    function show() {
      var which = orientation();
      var box = draft[which];
      frame.style.left = share(box.l, '%');
      frame.style.top = share(box.t, '%');
      frame.style.width = share(1 - box.l - box.r, '%');
      frame.style.height = share(1 - box.t - box.b, '%');
      title.textContent = which === 'portrait' ? 'Layout: upright' : 'Layout: sideways';
      apply(draft);
      announce();
    }
    function close() {
      root.remove();
      editor = null;
      apply(layout);
      announce();
      check();
    }

    editor = { show: show };
    document.body.appendChild(root);
    show();
  }

  window.__saveSyncLayout = { edit: edit };
  apply(layout);
  announce();
})
