"""Controlled HTTP target for the emulator's separate-UID TUN acceptance test."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from prepare_smart_config_meow_ci_fixture import load_verified_fixture
from uuid import UUID


class Handler(BaseHTTPRequestHandler):
    smart_files = {}

    def do_GET(self):
        prefix = "/smart-config-meow/"
        if self.path.startswith(prefix):
            relative = self.path[len(prefix):]
            body = self.smart_files.get(relative)
            if body is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        try:
            token = str(UUID(self.path.lstrip("/")))
        except ValueError:
            self.send_error(400)
            return
        body = f"AEROVE_LITE_FIXTURE:{token}".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--smart-config-fixture", type=Path)
    arguments = parser.parse_args()
    if arguments.smart_config_fixture:
        Handler.smart_files = load_verified_fixture(arguments.smart_config_fixture)
    ThreadingHTTPServer(("0.0.0.0", 18080), Handler).serve_forever()
