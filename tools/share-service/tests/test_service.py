"""The share service without the network: validation, quotas, the links, the completion against a fake S3 on
127.0.0.1, the HTTP layer; and the mod's own sending through it (ShareProbe, when tools/test_client.py compiled it)."""
import datetime
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import server  # noqa: E402
import sigv4  # noqa: E402
from fake_s3 import FakeS3  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
SECRET = "test-secret-not-a-real-one"
INST = "0123456789abcdef0123456789abcdef"
DAY = datetime.datetime(2026, 11, 8, 10, 0, tzinfo=datetime.timezone.utc)


def env(tmp, endpoint, **kw):
    e = {"S3_ENDPOINT": endpoint, "S3_REGION": "ru-1", "S3_BUCKET": "aiguide-share", "S3_ACCESS_KEY_ID": "TESTKEY",
         "S3_SECRET_ACCESS_KEY": SECRET, "SHARE_DB": os.path.join(tmp, "q.sqlite"), "SHARE_IP_SALT": "salt",
         "SHARE_MAX_SIZE": "100000", "SHARE_PER_INSTALL_DAY": "2", "SHARE_PER_IP_DAY": "3", "SHARE_DAY_COUNT": "4", "SHARE_DAY_BYTES": "250000"}
    e.update({k: str(v) for k, v in kw.items()})
    return e


def ask(data=b"x" * 100, inst=INST):
    return {"install_id": inst, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "manifest_summary": {"id": "abc", "files": 3}}


def put(url, data, headers):
    req = urllib.request.Request(url, data=data, method="PUT", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.s3 = FakeS3(SECRET)
        self.addCleanup(self.s3.close)
        self.clock = [DAY]

    def service(self, **kw):
        s = server.Service(server.Config(env(self.tmp, self.s3.url, **kw)), now=lambda: self.clock[0])
        self.addCleanup(s.db.close)
        return s


class Validation(Base):
    def test_bad(self):
        s = self.service()
        for body, what in [(None, "not an object"), ({**ask(), "install_id": "Steve"}, "install id"), ({**ask(), "size": "100"}, "size as text"),
                           ({**ask(), "size": 0}, "size 0"), ({**ask(), "size": True}, "a bool"), ({**ask(), "sha256": "ABC"}, "sha256"),
                           ({**ask(), "manifest_summary": {"x": "y" * 5000}}, "a big summary"), ({**ask(), "manifest_summary": []}, "a list")]:
            code, r = s.upload(body, "1.2.3.4")
            self.assertEqual((code, r["error"]), (400, "bad_request"), what)
        code, r = s.upload({**ask(), "size": 100001}, "1.2.3.4")
        self.assertEqual((code, r["error"], r["max_size"]), (413, "too_large", 100000))

    def test_not_configured(self):
        s = self.service(S3_BUCKET="")
        self.assertEqual(s.upload(ask(), "1.2.3.4")[0], 503)


class Quotas(Base):
    def test_per_install(self):
        s = self.service()
        self.assertEqual(s.upload(ask(), "1.1.1.1")[0], 200)
        self.assertEqual(s.upload(ask(), "1.1.1.2")[0], 200)
        code, r = s.upload(ask(), "1.1.1.3")
        self.assertEqual((code, r["error"], r["reason"], r["retry_after"]), (429, "quota", "install_daily", 14 * 3600))
        self.clock[0] = DAY + datetime.timedelta(days=1)
        self.assertEqual(s.upload(ask(), "1.1.1.3")[0], 200, "the next day: again")

    def test_per_ip_and_total(self):
        s = self.service()
        ids = ["%032x" % i for i in range(10)]
        for i in range(3):
            self.assertEqual(s.upload(ask(inst=ids[i]), "9.9.9.9")[0], 200)
        self.assertEqual(s.upload(ask(inst=ids[3]), "9.9.9.9")[1]["reason"], "ip_daily")
        self.assertEqual(s.upload(ask(inst=ids[4]), "8.8.8.8")[0], 200)   # the 4th of the day
        self.assertEqual(s.upload(ask(inst=ids[5]), "7.7.7.7")[1]["reason"], "total_daily")

    def test_bytes(self):
        s = self.service()
        big = b"y" * 100000
        self.assertEqual(s.upload(ask(big, "%032x" % 1), "1.0.0.1")[0], 200)
        self.assertEqual(s.upload(ask(big, "%032x" % 2), "1.0.0.2")[0], 200)
        self.assertEqual(s.upload(ask(big, "%032x" % 3), "1.0.0.3")[1]["reason"], "total_bytes")

    def test_ip_kept_hashed(self):
        s = self.service()
        s.upload(ask(), "203.0.113.7")
        db = sqlite3.connect(os.path.join(self.tmp, "q.sqlite"))
        rows = db.execute("SELECT * FROM uploads").fetchall()
        db.close()
        self.assertNotIn("203.0.113.7", json.dumps(rows))


class Links(Base):
    def setUp(self):
        super().setUp()
        self.clock = [datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)]   # the fake S3 checks a link's time by the real clock

    def test_link_and_complete(self):
        s = self.service()
        data = b"archive bytes " * 50
        code, r = s.upload(ask(data), "1.2.3.4")
        self.assertEqual(code, 200)
        u = urllib.parse.urlsplit(r["url"])
        self.assertRegex(u.path, r"^/aiguide-share/incoming/" + self.clock[0].strftime("%Y-%m-%d") + "/" + INST + r"/[0-9a-f]{32}\.zip$")
        q = dict(urllib.parse.parse_qsl(u.query))
        self.assertEqual((q["X-Amz-Expires"], q["X-Amz-SignedHeaders"]), ("900", "content-length;host"))
        self.assertEqual(r["headers"], {"Content-Length": str(len(data))})
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": INST}, "1.2.3.4"), (409, {"error": "not_uploaded"}))
        self.assertEqual(put(r["url"], data + b"!", {}), 403, "another length than the signed one")
        self.assertEqual(put(r["url"], data, {}), 200)
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": "f" * 32}, "1.2.3.4")[0], 404, "another install")
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": INST}, "1.2.3.4"), (200, {"status": "completed"}))
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": INST}, "1.2.3.4")[0], 200, "twice: still fine")

    def test_expired(self):
        s = self.service()
        data = b"z" * 10
        self.clock[0] -= datetime.timedelta(minutes=16)   # signed 16 minutes ago: past its 15
        r = s.upload(ask(data), "1.2.3.4")[1]
        self.assertEqual(put(r["url"], data, {}), 403)
        self.assertIn("expired", self.s3.refused)

    def test_size_mismatch(self):
        s = self.service()
        code, r = s.upload(ask(b"a" * 10), "1.2.3.4")
        self.s3.objects[urllib.parse.urlsplit(r["url"]).path] = b"a" * 11   # someone else's object there
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": INST}, "1.2.3.4"), (409, {"error": "size_mismatch"}))

    def test_checksum_signed(self):
        s = self.service(SHARE_SIGN_CHECKSUM=1)
        data = b"q" * 40
        r = s.upload(ask(data), "1.2.3.4")[1]
        self.assertIn("x-amz-checksum-sha256", r["headers"])
        self.assertIn("X-Amz-SignedHeaders=content-length%3Bhost%3Bx-amz-checksum-sha256", r["url"])
        self.assertEqual(put(r["url"], data, {}), 403, "the signed checksum must be sent")
        self.assertEqual(put(r["url"], b"Q" * 40, {"x-amz-checksum-sha256": r["headers"]["x-amz-checksum-sha256"]}), 403, "another body")
        self.assertEqual(put(r["url"], data, {"x-amz-checksum-sha256": r["headers"]["x-amz-checksum-sha256"]}), 200)
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": INST}, "")[0], 200)
        r2 = s.upload(ask(b"w" * 40), "1.2.3.4")[1]
        self.s3.objects[urllib.parse.urlsplit(r2["url"]).path] = b"v" * 40
        self.assertEqual(s.complete({"upload_id": r2["upload_id"], "install_id": INST}, "")[1], {"error": "checksum_mismatch"})

    def test_verify_download(self):
        s = self.service(SHARE_VERIFY_DOWNLOAD=1)
        r = s.upload(ask(b"m" * 30), "1.2.3.4")[1]
        self.s3.objects[urllib.parse.urlsplit(r["url"]).path] = b"n" * 30   # the same length, other bytes
        self.assertEqual(s.complete({"upload_id": r["upload_id"], "install_id": INST}, "")[1], {"error": "checksum_mismatch"})


class Http(Base):
    def start(self, **kw):
        svc = self.service(**kw)
        svc.now = lambda: datetime.datetime.now(datetime.timezone.utc)
        h = ThreadingHTTPServer(("127.0.0.1", 0), server.handler(svc))
        threading.Thread(target=h.serve_forever, daemon=True).start()
        self.addCleanup(h.server_close)
        self.addCleanup(h.shutdown)
        return f"http://127.0.0.1:{h.server_address[1]}", svc

    def call(self, url, body, headers=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read()), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}"), dict(e.headers)

    def test_layer(self):
        base, svc = self.start()
        with urllib.request.urlopen(base + "/healthz", timeout=10) as r:
            self.assertEqual(json.loads(r.read()), {"ok": True, "configured": True})
        self.assertEqual(self.call(base + "/v1/upload", b"{not json")[0], 400)
        self.assertEqual(self.call(base + "/v1/upload", b"x" * 20000)[0], 413)
        self.assertEqual(self.call(base + "/v1/nope", {})[0], 404)
        self.assertEqual(self.call(base + "/v1/upload", ask())[0], 200)
        self.assertEqual(self.call(base + "/v1/upload", ask())[0], 200)
        code, r, h = self.call(base + "/v1/upload", ask())
        self.assertEqual((code, r["reason"]), (429, "install_daily"))
        self.assertIn("Retry-After", h)

    def test_proxy_ip(self):
        base, svc = self.start(SHARE_PER_IP_DAY=1, SHARE_PER_INSTALL_DAY=9)
        self.assertEqual(self.call(base + "/v1/upload", ask(inst="%032x" % 1), {"X-Real-IP": "5.5.5.5"})[0], 200)
        # not trusted: X-Real-IP is ignored, the second ask comes from the same 127.0.0.1
        self.assertEqual(self.call(base + "/v1/upload", ask(inst="%032x" % 2), {"X-Real-IP": "6.6.6.6"})[1]["reason"], "ip_daily")
        base2, _ = self.start(SHARE_PER_IP_DAY=1, SHARE_PER_INSTALL_DAY=9, SHARE_TRUST_PROXY=1, SHARE_DB=os.path.join(self.tmp, "q2.sqlite"))
        self.assertEqual(self.call(base2 + "/v1/upload", ask(inst="%032x" % 3), {"X-Real-IP": "5.5.5.5"})[0], 200)
        self.assertEqual(self.call(base2 + "/v1/upload", ask(inst="%032x" % 4), {"X-Real-IP": "6.6.6.6"})[0], 200, "behind nginx: by X-Real-IP")

    def test_the_mods_sending(self):
        classes = os.path.join(ROOT, "build", "test-classes")
        if not os.path.isfile(os.path.join(classes, "hardelele", "aiguide", "client", "ShareProbe.class")):
            self.skipTest("run tools/test_client.py (it compiles ShareProbe)")
        base, svc = self.start(SHARE_SIGN_CHECKSUM=1)
        data = os.urandom(50000)
        z, m, cfg = (os.path.join(self.tmp, n) for n in ("a.zip", "manifest.json", "share.json"))
        for p, b in ((z, data), (m, json.dumps({"id": "e2e", "version": 1, "files": [{}], "consent": {"items": {"chat": False}}}).encode()),
                     (cfg, json.dumps({"shareService": base, "installId": INST, "timeoutSec": 10}).encode())):
            with open(p, "wb") as h:
                h.write(b)
        java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
        cp = os.pathsep.join([classes, os.path.join(ROOT, "lib", "gson-2.2.4.jar")])
        run = lambda: subprocess.run([java, "-cp", cp, "hardelele.aiguide.client.ShareProbe", cfg, z, m], capture_output=True, text=True, timeout=60)  # noqa: E731
        out = run()
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn("completed", out.stdout)
        (path, body), = self.s3.objects.items()
        self.assertEqual(body, data)
        row = svc.db.execute("SELECT status, summary FROM uploads").fetchone()
        self.assertEqual(row[0], "completed")
        self.assertEqual(json.loads(row[1])["id"], "e2e")
        run()
        out = run()   # the third of the day for this install
        self.assertEqual((out.returncode, out.stdout.split()[:3]), (3, ["REFUSED", "quota", "install_daily"]))


if __name__ == "__main__":
    unittest.main()
