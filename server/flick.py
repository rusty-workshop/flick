#!/usr/bin/env python3
"""
flick: preset editor for the Flick Wear OS app.

Serves one web page for adding/editing presets (open it on your phone or laptop)
and one JSON endpoint the watch syncs from. Stdlib only.

  GET  /              editor page (asks for the key once, then remembers it)
  GET  /api/presets   {"presets": [...]}            Bearer FLICK_KEY
  PUT  /api/presets   replace the whole list        Bearer FLICK_KEY
"""
import hmac
import json
import os
import re
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

KEY = os.environ["FLICK_KEY"]
PORT = int(os.environ.get("PORT", "8480"))
BIND = os.environ.get("BIND", "0.0.0.0")
HERE = Path(__file__).resolve().parent
DATA = Path(os.environ.get("DATA_DIR", HERE / "data"))
STORE = DATA / "presets.json"
PAGE = (HERE / "editor.html").read_bytes()

assert len(KEY) >= 32, "FLICK_KEY must be at least 32 characters"
DATA.mkdir(parents=True, exist_ok=True)
lock = threading.Lock()

SEED = [
    {"id": "site", "name": "My website", "emoji": "🐱", "kind": "url", "value": "https://rustyisacat.github.io"},
    {"id": "rick", "name": "Totally normal link", "emoji": "🎵", "kind": "url", "value": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"},
]
# An NDEF message has to fit in what readers will fetch; ~2 KB covers any sane URL or note.
MAX_VALUE = 2000


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def load() -> list:
    if not STORE.exists():
        save(SEED)
    return json.loads(STORE.read_text())["presets"]


def save(presets: list):
    tmp = STORE.with_suffix(".tmp")
    tmp.write_text(json.dumps({"presets": presets}, ensure_ascii=False, indent=1))
    tmp.replace(STORE)


def clean(raw) -> list:
    if not isinstance(raw, list) or len(raw) > 100:
        raise ValueError("expected a list of up to 100 presets")
    out, seen = [], set()
    for p in raw:
        if not isinstance(p, dict):
            raise ValueError("bad preset")
        kind = "text" if p.get("kind") == "text" else "url"
        name = str(p.get("name", "")).strip()[:60]
        value = str(p.get("value", "")).strip()
        emoji = str(p.get("emoji", "")).strip()[:8]
        pid = str(p.get("id", "")).strip()
        if not value:
            continue  # half-typed rows in the editor are just skipped
        if len(value) > MAX_VALUE:
            raise ValueError(f"'{name or value[:20]}' is too long")
        if kind == "url" and not re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", value):
            value = "https://" + value
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", pid) or pid in seen:
            pid = secrets.token_hex(4)
        seen.add(pid)
        out.append({"id": pid, "name": name or value[:40], "emoji": emoji, "kind": kind, "value": value})
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "flick"

    def log_message(self, fmt, *args):
        pass

    def send(self, code: int, body: bytes, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def js(self, code: int, obj):
        self.send(code, json.dumps(obj, ensure_ascii=False).encode())

    def authed(self) -> bool:
        got = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if hmac.compare_digest(got.encode(), KEY.encode()):
            return True
        self.js(401, {"error": "wrong key"})
        return False

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            return self.send(200, PAGE, "text/html; charset=utf-8")
        if path == "/api/presets":
            if not self.authed():
                return
            with lock:
                return self.js(200, {"presets": load()})
        if path == "/health":
            return self.js(200, {"ok": True})
        self.js(404, {"error": "not found"})

    def do_PUT(self):
        if self.path.split("?")[0] != "/api/presets":
            return self.js(404, {"error": "not found"})
        if not self.authed():
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 300_000:
                raise ValueError("too big")
            presets = clean(json.loads(self.rfile.read(n))["presets"])
        except (ValueError, KeyError, json.JSONDecodeError) as e:
            return self.js(400, {"error": str(e) or "bad request"})
        with lock:
            save(presets)
        log(f"saved {len(presets)} presets")
        self.js(200, {"presets": presets})


if __name__ == "__main__":
    with lock:
        load()
    log(f"flick on {BIND}:{PORT}")
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()
