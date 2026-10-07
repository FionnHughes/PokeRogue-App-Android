#!/usr/bin/env python3
"""A small store for PokéRogue run history, so a player's devices can share it.

The game keeps run history in each browser and never sends it to its server. The
patched Android app and a userscript in the desktop browser both talk to this
store: each sends the finished runs the store lacks and fetches the ones it lacks.

One finished run is one JSON object, {"entry": ..., "isVictory": ..., "isFavorite": ...},
kept under the time the run ended (the key the game itself uses). Each game account
has its own runs, so two people can use one store without seeing each other's.

  GET /pr/h/<account>/         -> {"runs": ["1790000000123", ...]}, newest first
  GET /pr/h/<account>/<key>    -> that run, or 404
  PUT /pr/h/<account>/<key>    -> stores that run; a run already stored is left as it is

Every request needs "Authorization: Bearer <code>". The code comes from the file
named in HUB_CODE_FILE (default: code.txt next to the stored runs).

Meant to sit behind a reverse proxy that serves HTTPS and passes /pr/h/* on.
Standard library only.

Environment: HUB_DIR (default /var/lib/pr-history), HUB_PORT (default 8791),
HUB_CODE_FILE, HUB_KEEP (how many runs to keep per account, default 100).
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
MAX_BODY = 512 * 1024
MIN_CODE = 12

DIR = Path(os.environ.get("HUB_DIR", "/var/lib/pr-history"))
PORT = int(os.environ.get("HUB_PORT", "8791"))
KEEP = int(os.environ.get("HUB_KEEP", "100"))
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


def is_run(value) -> bool:
    return (isinstance(value, dict) and set(value) == {"entry", "isVictory", "isFavorite"}
            and isinstance(value["entry"], dict))


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
        """What a request names: (the account's folder, the run's key or '' for the list).

        None if the path is not one of ours. Account names that differ only by case are one account.
        """
        path = self.path.split("?", 1)[0]
        if not path.startswith(PREFIX):
            return None
        account, slash, key = path[len(PREFIX):].partition("/")
        if not slash or not ACCOUNT.fullmatch(account) or not (key == "" or KEY.fullmatch(key)):
            return None
        return DIR / "accounts" / account.lower(), key

    def allowed(self) -> bool:
        given = self.headers.get("Authorization", "")
        return hmac.compare_digest(given.encode(), ("Bearer " + self.server.code).encode())

    def do_GET(self) -> None:
        target = self.target()
        if target is None:
            return self.reply(404)
        if not self.allowed():
            return self.reply(401)
        folder, key = target
        if key == "":
            return self.reply(200, json.dumps({"runs": stored_keys(folder)}).encode())
        try:
            self.reply(200, (folder / f"{key}.json").read_bytes())
        except FileNotFoundError:
            self.reply(404)

    def do_PUT(self) -> None:
        target = self.target()
        length = int(self.headers.get("Content-Length") or 0)
        # A request turned away before its body is read would leave that body in the
        # connection, to be mistaken for the next request. Such a connection is closed.
        self.close_connection = True
        if target is None or not target[1]:
            return self.reply(404)
        if not self.allowed():
            return self.reply(401)
        if length <= 0 or length > MAX_BODY:
            return self.reply(413)
        self.close_connection = False
        folder, key = target
        body = self.rfile.read(length)
        try:
            if not is_run(json.loads(body)):
                raise ValueError("not a finished run")
        except (ValueError, UnicodeDecodeError):
            return self.reply(400)
        stored = folder / f"{key}.json"
        if not stored.exists():
            folder.mkdir(parents=True, exist_ok=True)
            # Written under another name first, so a reader never sees half a file.
            partial = folder / f"{key}.json.part"
            partial.write_bytes(body)
            os.replace(partial, stored)
            for old in stored_keys(folder)[KEEP:]:
                (folder / f"{old}.json").unlink(missing_ok=True)
        self.reply(204)

    def log_message(self, pattern, *args) -> None:
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), pattern % args))


def main() -> None:
    DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.code = read_code()
    print(f"run history store on 127.0.0.1:{PORT}, keeping {KEEP} runs per account in {DIR}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
