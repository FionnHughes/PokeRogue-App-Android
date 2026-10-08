// Updates from my own server. latest.json there names the newest build and, per
// platform, the file to fetch with its size and SHA-256:
//   { "build": 3, "notes": "...",
//     "linux":   { "appimage": { "file": "PokeRogue.AppImage", "sha256": "...", "size": 123 } },
//     "windows": { "setup":    { "file": "PokeRogue-Setup.exe", "sha256": "...", "size": 123 } } }
// Files sit next to latest.json.
const crypto = require('crypto');
const fs = require('fs');

const UPDATE_URL = process.env.PR_UPDATE_URL || 'https://fionnhughes.dev/pr/d/latest.json';

/** The file this copy of the app updates itself from, or null if it cannot. */
function pick(latest, platform, appImagePath) {
  if (platform === 'win32') { return latest.windows?.setup || null; }
  if (platform === 'linux' && appImagePath) { return latest.linux?.appimage || null; }
  return null;
}

async function check(fetch) {
  const response = await fetch(UPDATE_URL + '?t=' + Date.now(), { cache: 'no-store' });
  if (!response.ok) { throw new Error('the update server answered ' + response.status); }
  const latest = await response.json();
  if (!Number.isInteger(latest.build)) { throw new Error('the update server sent something unexpected'); }
  return latest;
}

function fileUrl(name) { return new URL(name, UPDATE_URL).toString(); }

/** Downloads to dest and checks size and SHA-256 before it counts. */
async function download(fetch, file, dest, onProgress) {
  const response = await fetch(fileUrl(file.file), { cache: 'no-store' });
  if (!response.ok || !response.body) { throw new Error('the download answered ' + response.status); }
  const hash = crypto.createHash('sha256');
  const out = fs.createWriteStream(dest);
  let got = 0;
  try {
    for await (const chunk of response.body) {
      hash.update(chunk);
      got += chunk.length;
      if (!out.write(chunk)) { await new Promise((resolve) => out.once('drain', resolve)); }
      onProgress(file.size ? got / file.size : 0);
    }
  } finally {
    await new Promise((resolve) => out.end(resolve));
  }
  if (got !== file.size || hash.digest('hex') !== String(file.sha256).toLowerCase()) {
    fs.rmSync(dest, { force: true });
    throw new Error('the download did not match what the server described');
  }
  return dest;
}

module.exports = { check, pick, download, fileUrl, UPDATE_URL };
