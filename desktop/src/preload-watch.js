// Tells the app whether a text field has focus, so letter keys bound to tools stay
// letters while typing (a starter's name, a login, a search box).
const { ipcRenderer } = require('electron');

function editable(el) {
  if (!el) { return false; }
  if (el.isContentEditable) { return true; }
  const tag = (el.tagName || '').toLowerCase();
  if (tag === 'textarea' || tag === 'select') { return true; }
  if (tag !== 'input') { return false; }
  const type = (el.getAttribute('type') || 'text').toLowerCase();
  return !['button', 'checkbox', 'radio', 'range', 'submit', 'reset', 'image', 'file', 'color'].includes(type);
}

let last = null;
function report() {
  const now = editable(document.activeElement);
  if (now !== last) {
    last = now;
    ipcRenderer.send('typing', now);
  }
}
window.addEventListener('focusin', report, true);
window.addEventListener('focusout', () => setTimeout(report, 0), true);
window.addEventListener('DOMContentLoaded', report);
