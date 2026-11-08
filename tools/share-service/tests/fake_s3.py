"""A fake S3 on 127.0.0.1 for the tests: one bucket in memory; a PUT must be presigned right (sigv4.verify_presigned),
HEAD and GET signed in the Authorization header; a signed x-amz-checksum-sha256 is checked against the body."""
import base64
import hashlib
import os
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import sigv4  # noqa: E402


class FakeS3:
    def __init__(self, secret):
        self.secret = secret
        self.objects = {}
        self.refused = []
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def deny(self, why):
                fake.refused.append(why)
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def do_PUT(self):
                u = urllib.parse.urlsplit(self.path)
                ok, why = sigv4.verify_presigned("PUT", self.headers["Host"], u.path, u.query, dict(self.headers.items()), fake.secret)
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n)
                if not ok:
                    return self.deny(why)
                c = self.headers.get("x-amz-checksum-sha256")
                if c and base64.b64decode(c) != hashlib.sha256(body).digest():
                    return self.deny("checksum")
                fake.objects[u.path] = body
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def head_or_get(self, get):
                u = urllib.parse.urlsplit(self.path)
                if not sigv4.verify_headers("GET" if get else "HEAD", self.headers["Host"], u.path, u.query, dict(self.headers.items()), fake.secret):
                    return self.deny("bad header signature")
                b = fake.objects.get(u.path)
                if b is None:
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(b)))
                if self.headers.get("x-amz-checksum-mode") == "ENABLED":
                    self.send_header("x-amz-checksum-sha256", base64.b64encode(hashlib.sha256(b).digest()).decode())
                self.end_headers()
                if get:
                    self.wfile.write(b)

            def do_HEAD(self):
                self.head_or_get(False)

            def do_GET(self):
                self.head_or_get(True)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
