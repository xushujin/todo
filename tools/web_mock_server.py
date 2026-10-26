#!/usr/bin/env python3
"""Local mock of a GitHub-compatible contents API, plus a static server for web/.

Used to exercise `web/index.html` end to end without touching a real repository:

    python3 tools/web_mock_server.py --port 8765
    # open http://127.0.0.1:8765/
    # provider: GitHub · server: http://127.0.0.1:8765 · owner/repo: anything

Endpoints:
    GET  /repos/{owner}/{repo}/contents/{path}?ref=...   → 404 before the first write, then the file
    PUT  /repos/{owner}/{repo}/contents/{path}           → sha-checked write, 409 on stale sha
    GET  /__state                                        → in-memory store (for assertions)
    GET  /*                                              → files from web/
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import pathlib
import re
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
WEB_DIR = ROOT / "web"

STATE: dict[str, object] = {"content": None, "sha": None, "commits": [], "writes": 0}

CONTENTS_PATH = re.compile(r"^/repos/[^/]+/[^/]+/contents/(?P<path>.+?)(\?.*)?$")


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WEB_DIR), **kwargs)

    def log_message(self, fmt: str, *args) -> None:  # quieter output
        print(f"  {self.address_string()} {fmt % args}")

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 (http.server API)
        if self.path == "/__state":
            return self._json(200, STATE)
        if CONTENTS_PATH.match(self.path):
            if STATE["content"] is None:
                return self._json(404, {"message": "Not Found"})
            raw = str(STATE["content"]).encode("utf-8")
            return self._json(
                200,
                {
                    "name": "todos.json",
                    "path": "data/todos.json",
                    "sha": STATE["sha"],
                    "size": len(raw),
                    "encoding": "base64",
                    "content": base64.b64encode(raw).decode("ascii"),
                },
            )
        return super().do_GET()

    def do_PUT(self) -> None:  # noqa: N802
        if not CONTENTS_PATH.match(self.path):
            return self._json(405, {"message": "Method Not Allowed"})
        length = int(self.headers.get("Content-Length", "0"))
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return self._json(400, {"message": "invalid JSON"})
        if "content" not in payload or "message" not in payload:
            return self._json(422, {"message": "content and message are required"})
        expected = payload.get("sha")
        if STATE["sha"] is not None and expected != STATE["sha"]:
            return self._json(409, {"message": "sha does not match"})
        content = base64.b64decode(payload["content"]).decode("utf-8")
        sha = hashlib.sha1(content.encode("utf-8")).hexdigest()
        STATE["content"] = content
        STATE["sha"] = sha
        STATE["writes"] = int(STATE["writes"]) + 1
        commits = STATE["commits"]
        assert isinstance(commits, list)
        commits.append({"message": payload["message"], "sha": sha})
        return self._json(200, {"content": {"sha": sha, "path": "data/todos.json"}})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"web + mock contents API on http://127.0.0.1:{args.port}/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
