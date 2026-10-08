// Key bindings: how a pressed key is named, which keys the game itself uses, and
// whether a key can be bound to a tool. Pure functions, no Electron.

// The game's keyboard names (src/configs/inputs/cfg-keyboard-qwerty.ts) and the
// KeyboardEvent.code values they stand for.
const GAME_KEY_CODES = {
  KEY_PAGE_DOWN: ['PageDown'], KEY_PAGE_UP: ['PageUp'], KEY_CTRL: ['ControlLeft', 'ControlRight'],
  KEY_DEL: ['Delete'], KEY_END: ['End'], KEY_ENTER: ['Enter', 'NumpadEnter'], KEY_ESC: ['Escape'],
  KEY_HOME: ['Home'], KEY_INSERT: ['Insert'], KEY_PLUS: ['NumpadAdd'], KEY_MINUS: ['NumpadSubtract'],
  KEY_QUOTATION: ['Quote'], KEY_SHIFT: ['ShiftLeft', 'ShiftRight'], KEY_SPACE: ['Space'], KEY_TAB: ['Tab'],
  KEY_TILDE: ['Backquote'], KEY_ARROW_UP: ['ArrowUp'], KEY_ARROW_DOWN: ['ArrowDown'],
  KEY_ARROW_LEFT: ['ArrowLeft'], KEY_ARROW_RIGHT: ['ArrowRight'], KEY_LEFT_BRACKET: ['BracketLeft'],
  KEY_RIGHT_BRACKET: ['BracketRight'], KEY_SEMICOLON: ['Semicolon'], KEY_COMMA: ['Comma'],
  KEY_PERIOD: ['Period'], KEY_BACK_SLASH: ['Backslash'], KEY_FORWARD_SLASH: ['Slash'],
  KEY_BACKSPACE: ['Backspace'], KEY_ALT: ['AltLeft', 'AltRight']
};
for (const c of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') { GAME_KEY_CODES['KEY_' + c] = ['Key' + c]; }
for (let d = 0; d <= 9; d++) { GAME_KEY_CODES['KEY_' + d] = ['Digit' + d]; }
for (let f = 1; f <= 12; f++) { GAME_KEY_CODES['KEY_F' + f] = ['F' + f]; }

// The game's default keyboard layout: key -> what it does. Keys it leaves free are left out.
const GAME_DEFAULTS = {
  KEY_ARROW_UP: 'BUTTON_UP', KEY_ARROW_DOWN: 'BUTTON_DOWN', KEY_ARROW_LEFT: 'BUTTON_LEFT',
  KEY_ARROW_RIGHT: 'BUTTON_RIGHT', KEY_ENTER: 'BUTTON_SUBMIT', KEY_SPACE: 'BUTTON_ACTION',
  KEY_BACKSPACE: 'BUTTON_CANCEL', KEY_ESC: 'BUTTON_MENU', KEY_C: 'BUTTON_STATS',
  KEY_R: 'BUTTON_CYCLE_SHINY', KEY_F: 'BUTTON_CYCLE_FORM', KEY_G: 'BUTTON_CYCLE_GENDER',
  KEY_E: 'BUTTON_CYCLE_ABILITY', KEY_N: 'BUTTON_CYCLE_NATURE', KEY_V: 'BUTTON_CYCLE_TERA',
  KEY_A: 'ALT_BUTTON_LEFT', KEY_D: 'ALT_BUTTON_RIGHT', KEY_M: 'ALT_BUTTON_MENU', KEY_S: 'ALT_BUTTON_DOWN',
  KEY_T: 'ALT_BUTTON_CYCLE_FORM', KEY_W: 'ALT_BUTTON_UP', KEY_X: 'ALT_BUTTON_CANCEL',
  KEY_Y: 'ALT_BUTTON_CYCLE_SHINY', KEY_Z: 'ALT_BUTTON_ACTION', KEY_PAGE_DOWN: 'BUTTON_SLOW_DOWN',
  KEY_PAGE_UP: 'BUTTON_SPEED_UP', KEY_SHIFT: 'ALT_BUTTON_STATS'
};

// Keys this app keeps for itself.
const APP_KEYS = {
  F5: 'reload the game', F11: 'full screen', F12: 'developer tools'
};

// Keys never offered: modifiers alone, and keys that only make sense in text.
const UNBINDABLE = new Set(['ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight', 'AltLeft', 'AltRight',
  'MetaLeft', 'MetaRight', 'CapsLock', 'NumLock', 'ScrollLock', 'ContextMenu', 'Escape', '']);

/** "BUTTON_CYCLE_FORM" -> "cycle form", "ALT_BUTTON_UP" -> "up". */
function describeAction(action) {
  return String(action).replace(/^ALT_/, '').replace(/^BUTTON_/, '').replace(/_/g, ' ').toLowerCase();
}

/**
 * The keys the game uses, as code -> action, from its own stored layout when the
 * player changed it (localStorage "mappingConfigs", device "default") and its
 * defaults otherwise.
 */
function gameKeys(mappingConfigsText) {
  let layout = GAME_DEFAULTS;
  try {
    const custom = JSON.parse(mappingConfigsText || 'null')?.default?.custom;
    if (custom && typeof custom === 'object') { layout = custom; }
  } catch (e) {
    // an unreadable layout: the defaults are the best guess
  }
  const used = {};
  for (const [name, action] of Object.entries(layout)) {
    if (action === -1 || action === null || action === undefined || action === '') { continue; }
    for (const code of GAME_KEY_CODES[name] || []) { used[code] = describeAction(action); }
  }
  return used;
}

/** One binding from a key event: { code, ctrl, alt, shift }. */
function fromInput(input) {
  return { code: input.code || '', ctrl: !!(input.control || input.meta), alt: !!input.alt, shift: !!input.shift };
}

function same(a, b) {
  return !!a && !!b && a.code === b.code && !!a.ctrl === !!b.ctrl && !!a.alt === !!b.alt && !!a.shift === !!b.shift;
}

/** How a binding is shown: "T", "Ctrl+P", "F2", "Tab". */
function label(binding) {
  if (!binding || !binding.code) { return ''; }
  let name = binding.code.replace(/^Key/, '').replace(/^Digit/, '').replace(/^Numpad/, 'Num ').replace(/^Arrow/, '');
  const special = { Backquote: '`', Minus: '-', Equal: '=', BracketLeft: '[', BracketRight: ']',
    Backslash: '\\', Semicolon: ';', Quote: "'", Comma: ',', Period: '.', Slash: '/', Space: 'Space' };
  if (special[binding.code]) { name = special[binding.code]; }
  return (binding.ctrl ? 'Ctrl+' : '') + (binding.alt ? 'Alt+' : '') + (binding.shift ? 'Shift+' : '') + name;
}

/**
 * Whether a key can be bound to a tool (or the panel). Returns '' when it can, or
 * why not. A key held with Ctrl or Alt never reaches the game as itself, so only
 * plain (and Shift+) keys are checked against the game's layout.
 */
function refuse(binding, gameUsed, others) {
  if (!binding || UNBINDABLE.has(binding.code)) { return 'That key cannot be used on its own.'; }
  if (!binding.ctrl && !binding.alt && APP_KEYS[binding.code]) {
    return label(binding) + ' is kept for ' + APP_KEYS[binding.code] + '.';
  }
  if (!binding.ctrl && !binding.alt && gameUsed[binding.code]) {
    return 'The game uses ' + label({ code: binding.code }) + ' for "' + gameUsed[binding.code] + '". Pick another key, or hold Ctrl or Alt with it.';
  }
  for (const other of others || []) {
    if (same(other.binding, binding)) { return label(binding) + ' already opens ' + other.name + '.'; }
  }
  return '';
}

module.exports = { gameKeys, fromInput, same, label, refuse, describeAction, GAME_DEFAULTS, GAME_KEY_CODES };
