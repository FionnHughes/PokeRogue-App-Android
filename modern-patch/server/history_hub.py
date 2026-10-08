#!/usr/bin/env python3
"""A small store for what PokéRogue keeps only on each device, so a player's devices can share it.

The game keeps run history and settings in each browser and never sends them to its
server. The patched Android app and the desktop app (and a userscript in a desktop
browser, for run history) talk to this store. Each game account has its own folder,
so two people can use one store without seeing each other's data.

Run history: one finished run is one JSON object, {"entry": ..., "isVictory": ...,
"isFavorite": ...}, kept under the time the run ended (the key the game itself uses).
Each device sends the runs the store lacks and fetches the ones it lacks.

  GET /pr/h/<account>/         -> {"runs": ["1790000000123", ...]}, newest first
  GET /pr/h/<account>/<key>    -> that run, or 404
  PUT /pr/h/<account>/<key>    -> stores that run; a run already stored is left as it is

Settings: the settings every device of the account uses, {"updated": <ms>, "from":
"<device>", "data": {...}}. Until one is chosen, each device leaves its own settings
as a candidate, and the player picks one of them.

  GET /pr/h/<account>/settings                     -> the shared settings, or 404 if none chosen yet
  PUT /pr/h/<account>/settings                     -> replaces them; 409 (with the stored ones) if
                                                      the stored ones are newer
  GET /pr/h/<account>/settings/candidates/         -> {"candidates": [{"device", "from", "updated"}]}
  GET /pr/h/<account>/settings/candidates/<device> -> one candidate
  PUT /pr/h/<account>/settings/candidates/<device> -> leaves this device's settings as a candidate

Pages: the player's own list of web pages for the apps' side menus,
{"updated": <ms>, "pages": [{"name", "url"}, ...]}.

  GET /pr/h/<account>/pages   -> the list, or 404 if there is none yet
  PUT /pr/h/<account>/pages   -> replaces it; 409 (with the stored one) if the stored one is newer

Backups: copies of save data and runs in progress, {"from": "<device>", "made": <ms>, ...},
kept under "<ms>-<device>". The newest HUB_KEEP_BACKUPS of each device are kept.

  GET /pr/h/<account>/backups/      -> {"backups": [{"id", "from", "made", "size"}]}, newest first
  GET /pr/h/<account>/backups/<id>  -> one backup
  PUT /pr/h/<account>/backups/<id>  -> stores one

Every request needs "Authorization: Bearer <code>". The code comes from the file
named in HUB_CODE_FILE (default: code.txt next to the stored data).
Meant to sit behind a reverse proxy that serves HTTPS and passes /pr/h/* on.
Standard library only.

Environment: HUB_DIR (default /var/lib/pr-history), HUB_PORT (default 8791),
HUB_CODE_FILE, HUB_KEEP (runs kept per account, default 100), HUB_KEEP_BACKUPS
(backups kept per device of an account, default 30).
"""
import hmac
import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PREFIX = "/pr/h/"
KEY = re.compile(r"[0-9]{10,16}")
ACCOUNT = re.compile(r"[A-Za-z0-9_-]{1,32}")
DEVICE = re.compile(r"[a-z0-9-]{1,40}")
BACKUP = re.compile(r"[0-9]{10,16}-[a-z0-9-]{1,40}")
MIN_CODE = 12
LIMITS = {"run": 512 * 1024, "settings": 2 * 1024 * 1024, "candidate": 2 * 1024 * 1024, "backup": 16 * 1024 * 1024,
          "pages": 256 * 1024}

DIR = Path(os.environ.get("HUB_DIR", "/var/lib/pr-history"))
PORT = int(os.environ.get("HUB_PORT", "8791"))
KEEP = int(os.environ.get("HUB_KEEP", "100"))
KEEP_BACKUPS = int(os.environ.get("HUB_KEEP_BACKUPS", "30"))
CODE_FILE = Path(os.environ.get("HUB_CODE_FILE", str(DIR / "code.txt")))


def read_code() -> str:
    code = CODE_FILE.read_text(encoding="utf-8").strip()
    if len(code) < MIN_CODE:
        sys.exit(f"the code in {CODE_FILE} must be at least {MIN_CODE} characters")
    return code


def stored_keys(folder: Path) -> list:
    """The keys of an account's stored runs, newest first."""
    keys = [p.stem for p in folder.glob("*.json") if KEY.fullmatch(p.stem)]
    return sorted(keys, key=int, reverse=True)


def stored_backups(folder: Path) -> list:
    """An account's backup files, newest first."""
    files = [p for p in (folder / "backups").glob("*.json") if BACKUP.fullmatch(p.stem)]
    return sorted(files, key=lambda p: int(p.stem.split("-", 1)[0]), reverse=True)


def is_run(value) -> bool:
    return (isinstance(value, dict) and set(value) == {"entry", "isVictory", "isFavorite"}
            and isinstance(value["entry"], dict))


def is_settings(value) -> bool:
    return (isinstance(value, dict) and isinstance(value.get("updated"), int)
            and isinstance(value.get("from"), str) and isinstance(value.get("data"), dict))


def is_pages(value) -> bool:
    return (isinstance(value, dict) and isinstance(value.get("updated"), int) and isinstance(value.get("pages"), list)
            and all(isinstance(p, dict) and isinstance(p.get("name"), str) and isinstance(p.get("url"), str)
                    for p in value["pages"]))


def is_backup(value) -> bool:
    return isinstance(value, dict) and isinstance(value.get("made"), int) and isinstance(value.get("from"), str)


def write(path: Path, body: bytes) -> None:
    """Written under another name first, so a reader never sees half a file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".part")
    partial.write_bytes(body)
    os.replace(partial, path)


class Handler(BaseHTTPRequestHandler):
    server_version = "history-hub"
    protocol_version = "HTTP/1.1"

    def reply(self, status: int, body: bytes = b"", content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Robots-Tag", "noindex, nofollow")
        self.end_headers()
        self.wfile.write(body)

    def target(self):
        """What a request names: (kind, the account's folder, name), or None if the path is not ours.

        kind is "runs", "run", "settings", "candidates", "candidate", "backups" or "backup".
        Account names that differ only by case are one account.
        """
        path = self.path.split("?", 1)[0]
        if not path.startswith(PREFIX):
            return None
        account, slash, rest = path[len(PREFIX):].partition("/")
        if not slash or not ACCOUNT.fullmatch(account):
            return None
        folder = DIR / "accounts" / account.lower()
        if rest == "":
            return "runs", folder, ""
        if KEY.fullmatch(rest):
            return "run", folder, rest
        if rest == "settings":
            return "settings", folder, ""
        if rest == "pages":
            return "pages", folder, ""
        if rest == "settings/candidates/":
            return "candidates", folder, ""
        if rest.startswith("settings/candidates/") and DEVICE.fullmatch(rest[len("settings/candidates/"):]):
            return "candidate", folder, rest[len("settings/candidates/"):]
        if rest == "backups/":
            return "backups", folder, ""
        if rest.startswith("backups/") and BACKUP.fullmatch(rest[len("backups/"):]):
            return "backup", folder, rest[len("backups/"):]
        return None

    def allowed(self) -> bool:
        given = self.headers.get("Authorization", "")
        return hmac.compare_digest(given.encode(), ("Bearer " + self.server.code).encode())

    def send_file(self, path: Path) -> None:
        try:
            self.reply(200, path.read_bytes())
        except FileNotFoundError:
            self.reply(404)

    def do_GET(self) -> None:
        target = self.target()
        if target is None:
            return self.reply(404)
        if not self.allowed():
            return self.reply(401)
        kind, folder, name = target
        if kind == "runs":
            return self.reply(200, json.dumps({"runs": stored_keys(folder)}).encode())
        if kind == "run":
            return self.send_file(folder / f"{name}.json")
        if kind == "settings":
            return self.send_file(folder / "settings.json")
        if kind == "pages":
            return self.send_file(folder / "pages.json")
        if kind == "candidate":
            return self.send_file(folder / "candidates" / f"{name}.json")
        if kind == "candidates":
            found = []
            for path in sorted((folder / "candidates").glob("*.json")):
                try:
                    value = json.loads(path.read_bytes())
                    found.append({"device": path.stem, "from": value["from"], "updated": value["updated"]})
                except (OSError, ValueError, KeyError):
                    continue
            return self.reply(200, json.dumps({"candidates": found}).encode())
        if kind == "backups":
            found = []
            for path in stored_backups(folder):
                made, device = path.stem.split("-", 1)
                found.append({"id": path.stem, "from": device, "made": int(made), "size": path.stat().st_size})
            return self.reply(200, json.dumps({"backups": found}).encode())
        if kind == "backup":
            return self.send_file(folder / "backups" / f"{name}.json")
        self.reply(404)

    def do_PUT(self) -> None:
        target = self.target()
        length = int(self.headers.get("Content-Length") or 0)
        # A request turned away before its body is read would leave that body in the
        # connection, to be mistaken for the next request. Such a connection is closed.
        self.close_connection = True
        if target is None or target[0] not in LIMITS:
            return self.reply(404)
        if not self.allowed():
            return self.reply(401)
        kind, folder, name = target
        if length <= 0 or length > LIMITS[kind]:
            return self.reply(413)
        self.close_connection = False
        body = self.rfile.read(length)
        try:
            value = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            return self.reply(400)
        check = {"run": is_run, "settings": is_settings, "candidate": is_settings, "backup": is_backup,
                 "pages": is_pages}[kind]
        if not check(value):
            return self.reply(400)

        if kind == "run":
            stored = folder / f"{name}.json"
            if not stored.exists():
                write(stored, body)
                for old in stored_keys(folder)[KEEP:]:
                    (folder / f"{old}.json").unlink(missing_ok=True)
        elif kind in ("settings", "pages"):
            stored = folder / f"{kind}.json"
            with self.server.lock:
                try:
                    current = json.loads(stored.read_bytes())
                except (OSError, ValueError):
                    current = None
                if current and current.get("updated", 0) > value["updated"]:
                    # Someone changed them later than this device did: the later change stays.
                    return self.reply(409, json.dumps(current).encode())
                write(stored, body)
        elif kind == "candidate":
            write(folder / "candidates" / f"{name}.json", body)
        elif kind == "backup":
            write(folder / "backups" / f"{name}.json", body)
            device = name.split("-", 1)[1]
            same_device = [p for p in stored_backups(folder) if p.stem.split("-", 1)[1] == device]
            for old in same_device[KEEP_BACKUPS:]:
                old.unlink(missing_ok=True)
        self.reply(204)

    def log_message(self, pattern, *args) -> None:
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), pattern % args))


def main() -> None:
    import threading
    DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.code = read_code()
    server.lock = threading.Lock()
    print(f"store on 127.0.0.1:{PORT}: {KEEP} runs per account, {KEEP_BACKUPS} backups per device, in {DIR}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
