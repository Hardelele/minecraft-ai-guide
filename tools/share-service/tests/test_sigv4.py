"""SigV4 by hand against the examples of the AWS documentation (no network)."""
import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import sigv4  # noqa: E402

KEY = "AKIAIOSFODNN7EXAMPLE"
SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
NOW = datetime.datetime(2013, 5, 24, tzinfo=datetime.timezone.utc)


class Examples(unittest.TestCase):
    def test_signing_key(self):
        # "Deriving the signing key" (IAM, 2012-02-15)
        k = sigv4.signing_key("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20120215", "us-east-1", "iam")
        self.assertEqual(k.hex(), "f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d")

    def test_presigned_get(self):
        # S3 "Authenticating Requests: Using Query Parameters": GET test.txt, expires 86400
        u = sigv4.presign("GET", "https://s3.amazonaws.com", "examplebucket", "test.txt", "us-east-1", KEY, SECRET, 86400, now=NOW, path_style=False)
        self.assertTrue(u.startswith("https://examplebucket.s3.amazonaws.com/test.txt?"))
        self.assertIn("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", u)
        self.assertIn("X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request", u)

    def test_header_get(self):
        # S3 "Authenticating Requests: Using the Authorization Header": GET test.txt with Range bytes=0-9
        h = sigv4.sign_headers("GET", "https://examplebucket.s3.amazonaws.com/test.txt", "us-east-1", KEY, SECRET, {"range": "bytes=0-9"}, now=NOW)
        self.assertEqual(h["authorization"], "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                         "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")


class Ours(unittest.TestCase):
    def test_presigned_put_round_trip(self):
        u = sigv4.presign("PUT", "https://s3.example.org", "bucket", "incoming/2026-11-08/abc/x y.zip", "ru-1", KEY, SECRET, 900,
                          headers={"content-length": "123"}, now=NOW)
        path, query = u.split("s3.example.org", 1)[1].split("?", 1)
        self.assertEqual(path, "/bucket/incoming/2026-11-08/abc/x%20y.zip")
        self.assertIn("X-Amz-SignedHeaders=content-length%3Bhost", query)
        ok = lambda h, t=NOW: sigv4.verify_presigned("PUT", "s3.example.org", path, query, h, SECRET, t)[0]  # noqa: E731
        self.assertTrue(ok({"Content-Length": "123"}))
        self.assertFalse(ok({"Content-Length": "124"}), "another length is another signature")
        self.assertFalse(ok({}), "the signed length must be sent")
        self.assertFalse(ok({"Content-Length": "123"}, NOW + datetime.timedelta(seconds=901)), "expired")
        self.assertFalse(sigv4.verify_presigned("PUT", "s3.example.org", path, query, {"Content-Length": "123"}, "other", NOW)[0])

    def test_header_round_trip(self):
        h = sigv4.sign_headers("HEAD", "http://127.0.0.1:9000/bucket/a.zip", "ru-1", KEY, SECRET, {})
        self.assertTrue(sigv4.verify_headers("HEAD", "127.0.0.1:9000", "/bucket/a.zip", "", h, SECRET))
        self.assertFalse(sigv4.verify_headers("GET", "127.0.0.1:9000", "/bucket/a.zip", "", h, SECRET))


if __name__ == "__main__":
    unittest.main()
