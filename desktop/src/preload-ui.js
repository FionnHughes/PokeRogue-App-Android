// The side panel's link to the app.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('app', {
  state: () => ipcRenderer.invoke('ui:state'),
  onState: (callback) => ipcRenderer.on('state', (event, state) => callback(state)),
  close: () => ipcRenderer.invoke('ui:close'),
  closePanel: () => ipcRenderer.invoke('ui:closePanel'),
  closeSheet: () => ipcRenderer.invoke('ui:closeSheet'),
  tool: (id) => ipcRenderer.invoke('ui:tool', id),
  toggle: (name) => ipcRenderer.invoke('ui:toggle', name),
  capturing: (on) => ipcRenderer.invoke('ui:capturing', on),
  bind: (target, binding) => ipcRenderer.invoke('ui:bind', target, binding),
  setCode: (code) => ipcRenderer.invoke('ui:setCode', code),
  shareNow: () => ipcRenderer.invoke('ui:shareNow'),
  candidates: () => ipcRenderer.invoke('ui:candidates'),
  choose: (device) => ipcRenderer.invoke('ui:choose', device),
  applySettings: () => ipcRenderer.invoke('ui:applySettings'),
  changePage: (index, entry) => ipcRenderer.invoke('ui:changePage', index, entry),
  copyCaught: () => ipcRenderer.invoke('ui:copyCaught'),
  copyTeam: (slot) => ipcRenderer.invoke('ui:copyTeam', slot),
  checkUpdates: () => ipcRenderer.invoke('ui:checkUpdates'),
  reload: () => ipcRenderer.invoke('ui:reload'),
  nav: (action) => ipcRenderer.invoke('ui:nav', action),
  typing: (on) => ipcRenderer.send('typing', on)
});
