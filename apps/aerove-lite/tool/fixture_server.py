"""Controlled HTTP target for the emulator's separate-UID TUN acceptance test."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from prepare_smart_config_meow_ci_fixture import load_verified_fixture
from profile_cancel_fixture import CancelFixture
from uuid import UUID
from urllib.parse import urlsplit, parse_qs
import json
import os
import hashlib
import threading
import time


def load_smart_fixture(root, owner="current"):
    if owner == "current":
        return load_verified_fixture(root)
    if owner == "dba5":
        from prepare_smart_config_meow_dba5_ci_fixture import load_verified_fixture as load_dba5
        return load_dba5(root)
    if owner == "legacy":
        from prepare_smart_config_meow_legacy_ci_fixture import load_verified_fixture as load_legacy
        return load_legacy(root)
    raise ValueError("Unknown Smart fixture owner")


class Handler(BaseHTTPRequestHandler):
    cancel_fixture = CancelFixture()
    smart_files = {}
    profile_releases = {}
    profile_lock = threading.Lock()
    event_file = None

    def profile_event(self, token, event):
        if self.event_file:
            with self.profile_lock, self.event_file.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(dict(token=token, event=event, monotonicNs=time.monotonic_ns())) + "\n")

    def do_POST(self):
        prefix = "/flutter-profile-release/"
        try:
            if not self.path.startswith(prefix):
                raise ValueError("Unknown route")
            token = str(UUID(self.path[len(prefix):]))
            with self.profile_lock:
                release = self.profile_releases.get(token)
            if release is None:
                self.send_error(404)
                return
            release.set()
            self.send_response(204)
            self.end_headers()
        except ValueError:
            self.send_error(400)

    def flutter_profile(self):
        url = urlsplit(self.path)
        slow = url.path.startswith("/flutter-profile-slow/")
        prefix = "/flutter-profile-slow/" if slow else "/flutter-profile/"
        if not url.path.startswith(prefix):
            return False
        try:
            name = url.path[len(prefix):]
            if not name.endswith(".yaml"):
                raise ValueError("YAML profile required")
            token = str(UUID(name[:-5]))
            query = parse_qs(url.query, strict_parsing=True)
            if set(query) != {"mixedPort"} or len(query["mixedPort"]) != 1:
                raise ValueError("One explicit mixedPort required")
            port = int(query["mixedPort"][0])
            if not 1024 <= port <= 65535 or port == 18080:
                raise ValueError("Unsafe mixedPort")
        except (ValueError, KeyError):
            self.send_error(400)
            return True
        body = f"mixed-port: {port}\nmode: direct\nrules: ['MATCH,DIRECT']\n".encode()
        release = threading.Event()
        if slow:
            with self.profile_lock:
                if token in self.profile_releases:
                    self.send_error(409)
                    return True
                self.profile_releases[token] = release
        self.send_response(200)
        self.send_header("Content-Type", "application/yaml")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.profile_event(token, "headers")
        try:
            if slow:
                self.wfile.write(body[:1]); self.wfile.flush()
                self.profile_event(token, "first-byte")
                if not release.wait(15):
                    self.profile_event(token, "bounded-release-timeout")
                    self.close_connection = True
                    return True
                self.wfile.write(body[1:])
            else:
                self.wfile.write(body)
            self.profile_event(token, "complete")
        except (BrokenPipeError, ConnectionResetError):
            self.profile_event(token, "peer-closed")
        return True

    def do_GET(self):
        if self.cancel_fixture.handle(self):
            return
        if self.flutter_profile():
            return
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
    parser.add_argument("--smart-config-fixture-owner", choices=("current", "dba5", "legacy"), default="current")
    parser.add_argument("--profile-events", type=Path)
    parser.add_argument("--ready-file", type=Path)
    parser.add_argument("--owner-token")
    arguments = parser.parse_args()
    if bool(arguments.ready_file) != bool(arguments.owner_token):
        parser.error("ready-file and owner-token must be paired")
    Handler.event_file = arguments.profile_events
    if arguments.smart_config_fixture:
        Handler.smart_files = load_smart_fixture(arguments.smart_config_fixture, arguments.smart_config_fixture_owner)
    with ThreadingHTTPServer(("0.0.0.0", 18080), Handler) as server:
        if arguments.ready_file:
            token = str(UUID(arguments.owner_token))
            pending = arguments.ready_file.with_suffix(".partial")
            payload = dict(schema=1, pid=os.getpid(), ownerToken=token, boundAddress=server.server_address[0],
                           boundPort=server.server_port, sourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
            with pending.open("x", encoding="utf-8") as stream:
                stream.write(json.dumps(payload) + "\n"); stream.flush(); os.fsync(stream.fileno())
            if arguments.ready_file.exists():
                raise ValueError("Refuse fixture readiness overwrite")
            pending.rename(arguments.ready_file)
        server.serve_forever()
