"""AI Guide share service: hands out one-time upload links to a private S3 bucket (the standard library only).

    POST /v1/upload    {"install_id", "size", "sha256", "manifest_summary"}
                       -> 200 {"upload_id", "url", "method": "PUT", "headers": {...}, "expires_in"}
                       -> 400 bad_request, 413 too_large, 429 quota (reason, retry_after), 503 not_configured
    POST /v1/complete  {"upload_id", "install_id"}
                       -> 200 {"status": "completed"}; 409 not_uploaded / size_mismatch / checksum_mismatch; 404 unknown
    GET  /healthz      -> 200 ok

The link is a presigned PUT (SigV4), valid S3_URL_TTL seconds (900), to incoming/<yyyy-mm-dd>/<install_id>/<uuid>.zip;
Content-Length is a signed header (S3 refuses another length); with SHARE_SIGN_CHECKSUM=1 so is x-amz-checksum-sha256
(the client sends the archive's SHA-256, base64), where the storage supports it. /v1/complete checks the object by
HEAD (its length; its checksum when the storage gives one back) and, with SHARE_VERIFY_DOWNLOAD=1, downloads it and
checks the SHA-256 itself.

Quotas per UTC day, kept in sqlite: links per install, per client IP (kept only as a salted hash), all links and all
bytes. A link counts when it is given, uploaded or not. Everything is configured by the environment (README.md).
"""
import base64
import datetime
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sigv4  # noqa: E402

INSTALL_ID = re.compile(r"^[0-9a-f]{32}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MAX_BODY = 16 * 1024
MAX_SUMMARY = 4 * 1024


def env_int(env, name, default):
    v = env.get(name, "")
    return int(v) if v.strip() else default


class Config:
    def __init__(self, env=None):
        env = os.environ if env is None else env
        self.endpoint = env.get("S3_ENDPOINT", "").rstrip("/")
        self.region = env.get("S3_REGION", "")
        self.bucket = env.get("S3_BUCKET", "")
        self.key_id = env.get("S3_ACCESS_KEY_ID", "")
        self.secret = env.get("S3_SECRET_ACCESS_KEY", "")
        self.path_style = env.get("S3_PATH_STYLE", "1") != "0"
        self.ttl = env_int(env, "S3_URL_TTL", 900)
        self.prefix = env.get("S3_PREFIX", "incoming").strip("/")
        self.sign_checksum = env.get("SHARE_SIGN_CHECKSUM", "0") == "1"
        self.verify_download = env.get("SHARE_VERIFY_DOWNLOAD", "0") == "1"
        self.db = env.get("SHARE_DB", "share.sqlite")
        self.bind = env.get("SHARE_BIND", "127.0.0.1")
        self.port = env_int(env, "SHARE_PORT", 8080)
        self.trust_proxy = env.get("SHARE_TRUST_PROXY", "0") == "1"
        self.ip_salt = env.get("SHARE_IP_SALT", "")
        self.max_size = env_int(env, "SHARE_MAX_SIZE", 200 * 1024 * 1024)
        self.per_install = env_int(env, "SHARE_PER_INSTALL_DAY", 3)
        self.per_ip = env_int(env, "SHARE_PER_IP_DAY", 10)
        self.day_count = env_int(env, "SHARE_DAY_COUNT", 500)
        self.day_bytes = env_int(env, "SHARE_DAY_BYTES", 20 * 1024 ** 3)

    def ready(self):
        return all([self.endpoint, self.region, self.bucket, self.key_id, self.secret])


class Service:
    def __init__(self, cfg, now=None, opener=None):
        self.cfg = cfg
        self.now = now or (lambda: datetime.datetime.now(datetime.timezone.utc))
        self.opener = opener or urllib.request.urlopen
        if not cfg.ip_salt:   # quotas by IP then hold until a restart only
            print("share-service: SHARE_IP_SALT not set, a random one is used", file=sys.stderr)
            cfg.ip_salt = secrets.token_hex(16)
        self.lock = threading.Lock()
        self.db = sqlite3.connect(cfg.db, check_same_thread=False)
        self.db.execute("""CREATE TABLE IF NOT EXISTS uploads (
            id TEXT PRIMARY KEY, install_id TEXT NOT NULL, ip TEXT NOT NULL, day TEXT NOT NULL, size INTEGER NOT NULL,
            sha256 TEXT NOT NULL, key TEXT NOT NULL, summary TEXT, created TEXT NOT NULL, completed TEXT, status TEXT NOT NULL)""")
        self.db.execute("CREATE INDEX IF NOT EXISTS uploads_day ON uploads(day)")
        self.db.commit()

    def ip_hash(self, ip):
        return hmac.new(self.cfg.ip_salt.encode(), ip.encode(), hashlib.sha256).hexdigest()[:32]

    # ---- the two calls ------------------------------------------------------------------------------------------

    def upload(self, body, ip):
        if not self.cfg.ready():
            return 503, {"error": "not_configured", "message": "the service has no storage configured"}
        err = validate_upload(body, self.cfg.max_size)
        if err:
            return err
        now = self.now()
        day = now.strftime("%Y-%m-%d")
        size, inst = int(body["size"]), body["install_id"]
        iph = self.ip_hash(ip)
        retry = int((datetime.datetime.combine(now.date() + datetime.timedelta(days=1), datetime.time(), now.tzinfo) - now).total_seconds())
        with self.lock:
            c = self.db.cursor()
            n_inst = c.execute("SELECT COUNT(*) FROM uploads WHERE day=? AND install_id=?", (day, inst)).fetchone()[0]
            n_ip = c.execute("SELECT COUNT(*) FROM uploads WHERE day=? AND ip=?", (day, iph)).fetchone()[0]
            n_all, b_all = c.execute("SELECT COUNT(*), COALESCE(SUM(size),0) FROM uploads WHERE day=?", (day,)).fetchone()
            for over, reason in ((n_inst >= self.cfg.per_install, "install_daily"), (n_ip >= self.cfg.per_ip, "ip_daily"),
                                 (n_all >= self.cfg.day_count, "total_daily"), (b_all + size > self.cfg.day_bytes, "total_bytes")):
                if over:
                    return 429, {"error": "quota", "reason": reason, "retry_after": retry}
            uid = uuid.uuid4().hex
            key = f"{self.cfg.prefix}/{day}/{inst}/{uid}.zip"
            headers = {"content-length": str(size)}
            if self.cfg.sign_checksum:
                headers["x-amz-checksum-sha256"] = base64.b64encode(bytes.fromhex(body["sha256"])).decode()
            url = sigv4.presign("PUT", self.cfg.endpoint, self.cfg.bucket, key, self.cfg.region, self.cfg.key_id, self.cfg.secret,
                                self.cfg.ttl, headers=headers, now=now, path_style=self.cfg.path_style)
            c.execute("INSERT INTO uploads VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                      (uid, inst, iph, day, size, body["sha256"], key, json.dumps(body.get("manifest_summary") or {}, ensure_ascii=False),
                       now.isoformat(), None, "issued"))
            self.db.commit()
        send = {"Content-Length": str(size)}
        if "x-amz-checksum-sha256" in headers:
            send["x-amz-checksum-sha256"] = headers["x-amz-checksum-sha256"]
        return 200, {"upload_id": uid, "url": url, "method": "PUT", "headers": send, "expires_in": self.cfg.ttl}

    def complete(self, body, ip):
        if not isinstance(body, dict) or not isinstance(body.get("upload_id"), str) or not INSTALL_ID.match(str(body.get("install_id", ""))):
            return 400, {"error": "bad_request", "message": "upload_id and install_id"}
        with self.lock:
            row = self.db.execute("SELECT install_id, key, size, sha256, status FROM uploads WHERE id=?", (body["upload_id"],)).fetchone()
        if row is None or row[0] != body["install_id"]:
            return 404, {"error": "unknown", "message": "no such upload"}
        _, key, size, sha, status = row
        if status == "completed":
            return 200, {"status": "completed"}
        problem = self.check_object(key, size, sha)
        if problem:
            return 409, {"error": problem}
        with self.lock:
            self.db.execute("UPDATE uploads SET status='completed', completed=? WHERE id=?", (self.now().isoformat(), body["upload_id"]))
            self.db.commit()
        return 200, {"status": "completed"}

    def check_object(self, key, size, sha):
        scheme, host, path = sigv4.object_url(self.cfg.endpoint, self.cfg.bucket, key, self.cfg.path_style)
        url = f"{scheme}://{host}{path}"
        extra = {"x-amz-checksum-mode": "ENABLED"} if self.cfg.sign_checksum else {}
        h = sigv4.sign_headers("HEAD", url, self.cfg.region, self.cfg.key_id, self.cfg.secret, extra, now=self.now())
        try:
            r = self.opener(urllib.request.Request(url, method="HEAD", headers=h), timeout=30)
            got_len, got_sum = r.headers.get("Content-Length"), r.headers.get("x-amz-checksum-sha256")
            r.close()
        except urllib.error.HTTPError as e:
            return "not_uploaded" if e.code == 404 else f"storage_{e.code}"
        if got_len is None or int(got_len) != size:
            return "size_mismatch"
        if got_sum and base64.b64decode(got_sum).hex() != sha:
            return "checksum_mismatch"
        if self.cfg.verify_download:
            g = sigv4.sign_headers("GET", url, self.cfg.region, self.cfg.key_id, self.cfg.secret, {}, now=self.now())
            d = hashlib.sha256()
            with self.opener(urllib.request.Request(url, headers=g), timeout=300) as r:
                for chunk in iter(lambda: r.read(1 << 20), b""):
                    d.update(chunk)
            if d.hexdigest() != sha:
                return "checksum_mismatch"
        return None


def validate_upload(body, max_size):
    """(status, answer) for a bad request, None for a good one"""
    bad = lambda m: (400, {"error": "bad_request", "message": m})  # noqa: E731
    if not isinstance(body, dict):
        return bad("a JSON object")
    if not INSTALL_ID.match(str(body.get("install_id", ""))):
        return bad("install_id: 32 hex digits")
    size = body.get("size")
    if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
        return bad("size: a positive integer")
    if size > max_size:
        return 413, {"error": "too_large", "max_size": max_size}
    if not SHA256.match(str(body.get("sha256", ""))):
        return bad("sha256: 64 hex digits, lower case")
    s = body.get("manifest_summary", {})
    if not isinstance(s, dict) or len(json.dumps(s)) > MAX_SUMMARY:
        return bad(f"manifest_summary: an object under {MAX_SUMMARY} bytes")
    return None


def handler(service):
    class H(BaseHTTPRequestHandler):
        server_version = "aiguide-share/1"

        def client_ip(self):
            if service.cfg.trust_proxy and self.headers.get("X-Real-IP"):
                return self.headers["X-Real-IP"].strip()
            return self.client_address[0]

        def answer(self, code, obj):
            b = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            if code == 429 and "retry_after" in obj:
                self.send_header("Retry-After", str(obj["retry_after"]))
            self.end_headers()
            self.wfile.write(b)

        def do_GET(self):
            if self.path == "/healthz":
                return self.answer(200, {"ok": True, "configured": service.cfg.ready()})
            self.answer(404, {"error": "not_found"})

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > MAX_BODY:
                return self.answer(413, {"error": "too_large", "message": "request body"})
            try:
                body = json.loads(self.rfile.read(n) or b"null")
            except ValueError:
                return self.answer(400, {"error": "bad_request", "message": "not JSON"})
            if self.path == "/v1/upload":
                return self.answer(*service.upload(body, self.client_ip()))
            if self.path == "/v1/complete":
                return self.answer(*service.complete(body, self.client_ip()))
            self.answer(404, {"error": "not_found"})

        def log_message(self, fmt, *args):   # the request and its code, no client IP
            sys.stderr.write("share-service: " + (fmt % args) + "\n")

        def address_string(self):
            return "-"

    return H


def serve(cfg=None):
    cfg = cfg or Config()
    s = ThreadingHTTPServer((cfg.bind, cfg.port), handler(Service(cfg)))
    print(f"share-service on {cfg.bind}:{cfg.port}, storage {'configured' if cfg.ready() else 'NOT configured'}", file=sys.stderr)
    s.serve_forever()


if __name__ == "__main__":
    serve()
