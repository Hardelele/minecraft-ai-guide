"""AWS Signature Version 4 for S3, by hand (the standard library only): presigned URLs (query auth) and signed headers.

    url = presign("PUT", "https://s3.example", "bucket", "incoming/a.zip", "ru-1", key_id, secret, 900,
                  headers={"content-length": "123"})
    h = sign_headers("HEAD", "https://s3.example/bucket/incoming/a.zip", "ru-1", key_id, secret, {})

Checked against the examples of the AWS documentation (tests/test_sigv4.py).
"""
import datetime
import hashlib
import hmac
import urllib.parse

ALGORITHM = "AWS4-HMAC-SHA256"
UNSIGNED = "UNSIGNED-PAYLOAD"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()


def _hmac(key, msg):
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def signing_key(secret, date, region, service="s3"):
    k = _hmac(("AWS4" + secret).encode("utf-8"), date)
    k = _hmac(k, region)
    k = _hmac(k, service)
    return _hmac(k, "aws4_request")


def quote(s, path=False):
    """S3's URI encoding: everything but A-Z a-z 0-9 - _ . ~ (and '/' in a path)"""
    return urllib.parse.quote(s, safe="-_.~" + ("/" if path else ""))


def canonical_query(params):
    return "&".join(f"{quote(k)}={quote(v)}" for k, v in sorted((str(k), str(v)) for k, v in params.items()))


def stamp(now=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    return now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")


def object_url(endpoint, bucket, key, path_style=True):
    """(scheme, host, path) of an object: path style https://host/bucket/key, or virtual hosted https://bucket.host/key"""
    u = urllib.parse.urlsplit(endpoint)
    if path_style:
        return u.scheme, u.netloc, "/" + bucket + "/" + quote(key, path=True)
    return u.scheme, bucket + "." + u.netloc, "/" + quote(key, path=True)


def _canon_headers(headers):
    hs = {k.lower().strip(): " ".join(str(v).split()) for k, v in headers.items()}
    names = sorted(hs)
    return "".join(f"{n}:{hs[n]}\n" for n in names), ";".join(names)


def signature(method, path, query, headers, payload_hash, secret, amz_date, region, service="s3"):
    canon_h, signed = _canon_headers(headers)
    creq = "\n".join([method, path, query, canon_h, signed, payload_hash])
    scope = f"{amz_date[:8]}/{region}/{service}/aws4_request"
    sts = "\n".join([ALGORITHM, amz_date, scope, hashlib.sha256(creq.encode("utf-8")).hexdigest()])
    return hmac.new(signing_key(secret, amz_date[:8], region, service), sts.encode("utf-8"), hashlib.sha256).hexdigest(), signed


def presign_path(method, host, path, region, key_id, secret, expires, headers=None, now=None, service="s3"):
    """the query of a presigned request (headers: what the client must send as signed, e.g. content-length)"""
    amz_date, date = stamp(now)
    hs = {"host": host}
    hs.update({k.lower(): v for k, v in (headers or {}).items()})
    _, signed = _canon_headers(hs)
    q = {"X-Amz-Algorithm": ALGORITHM, "X-Amz-Credential": f"{key_id}/{date}/{region}/{service}/aws4_request",
         "X-Amz-Date": amz_date, "X-Amz-Expires": str(int(expires)), "X-Amz-SignedHeaders": signed}
    sig, _ = signature(method, path, canonical_query(q), hs, UNSIGNED, secret, amz_date, region, service)
    q["X-Amz-Signature"] = sig
    return canonical_query(q)


def presign(method, endpoint, bucket, key, region, key_id, secret, expires, headers=None, now=None, path_style=True):
    scheme, host, path = object_url(endpoint, bucket, key, path_style)
    return f"{scheme}://{host}{path}?" + presign_path(method, host, path, region, key_id, secret, expires, headers, now)


def sign_headers(method, url, region, key_id, secret, headers, payload_hash=EMPTY_SHA256, now=None, service="s3"):
    """the headers of a request signed in its Authorization header (x-amz-date and x-amz-content-sha256 added)"""
    u = urllib.parse.urlsplit(url)
    amz_date, date = stamp(now)
    hs = {"host": u.netloc}
    hs.update({k.lower(): v for k, v in headers.items()})
    hs["x-amz-date"] = amz_date
    hs["x-amz-content-sha256"] = payload_hash
    q = canonical_query(dict(urllib.parse.parse_qsl(u.query, keep_blank_values=True)))
    sig, signed = signature(method, u.path or "/", q, hs, payload_hash, secret, amz_date, region, service)
    out = {k: v for k, v in hs.items() if k != "host"}
    out["authorization"] = f"{ALGORITHM} Credential={key_id}/{date}/{region}/{service}/aws4_request, SignedHeaders={signed}, Signature={sig}"
    return out


def verify_presigned(method, host, path, query, headers, secret, now=None):
    """for a fake S3 in the tests: (True, "") when the presigned request is signed right, in time, with its headers"""
    q = dict(urllib.parse.parse_qsl(query, keep_blank_values=True))
    try:
        sig = q.pop("X-Amz-Signature")
        amz_date, expires = q["X-Amz-Date"], int(q["X-Amz-Expires"])
        _, date, region, service, _ = q["X-Amz-Credential"].split("/")
        names = q["X-Amz-SignedHeaders"].split(";")
    except (KeyError, ValueError):
        return False, "not presigned"
    t = datetime.datetime.strptime(amz_date, "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc)
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if now > t + datetime.timedelta(seconds=expires):
        return False, "expired"
    low = {k.lower(): v for k, v in headers.items()}
    low["host"] = host
    if any(n not in low for n in names):
        return False, "a signed header is missing"
    want, _ = signature(method, path, canonical_query(q), {n: low[n] for n in names}, UNSIGNED, secret, amz_date, region, service)
    return hmac.compare_digest(want, sig), "" if hmac.compare_digest(want, sig) else "bad signature"


def verify_headers(method, host, path, query, headers, secret):
    """the same for a request signed in its Authorization header"""
    low = {k.lower(): v for k, v in headers.items()}
    a = low.get("authorization", "")
    try:
        parts = dict(p.strip().split("=", 1) for p in a[len(ALGORITHM) + 1:].split(","))
        _, date, region, service, _ = parts["Credential"].split("/")
        names, sig = parts["SignedHeaders"].split(";"), parts["Signature"]
    except (KeyError, ValueError):
        return False
    low["host"] = host
    if any(n not in low for n in names):
        return False
    q = canonical_query(dict(urllib.parse.parse_qsl(query, keep_blank_values=True)))
    want, _ = signature(method, path, q, {n: low[n] for n in names}, low.get("x-amz-content-sha256", UNSIGNED), secret, low.get("x-amz-date", ""), region, service)
    return hmac.compare_digest(want, sig)
