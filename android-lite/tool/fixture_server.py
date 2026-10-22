"""Controlled HTTP target for the emulator's separate-UID TUN acceptance test."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
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
    ThreadingHTTPServer(("0.0.0.0", 18080), Handler).serve_forever()
