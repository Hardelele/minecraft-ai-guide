"""Checks of tools/labels.py (the person's ratings into training sets) on made-up records, and on the records the
Java side wrote in its own test (build/test-tmp/labels, when tools/test_client.py ran FeedbackTest first)."""
import base64
import json
import os
import shutil
import sys
import tempfile
import unittest
import zipfile
import zlib

import numpy as np

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, TOOLS)
import cases  # noqa: E402
import labels  # noqa: E402

ROOT = os.path.dirname(TOOLS)


def tmp(test):
    d = tempfile.mkdtemp()
    test.addCleanup(shutil.rmtree, d, True)
    return d


def cube():
    """stone below y 10, a built floor at 10, air above: [x][y][z]"""
    c = np.zeros((32, 32, 32), dtype=np.uint8)
    c[:, :10, :] = 5
    c[:, 10, :] = 9
    return c


def packed(c):
    return base64.b64encode(zlib.compress(c.tobytes(), 9)).decode()


def place(ms, pid, answer, label_i, box=(4, 11, 4, 9, 14, 9), pred_i=3, with_cube=True, origin=(0, 0, 0)):
    r = {"v": 1, "kind": "place", "time": "2026-11-08T00:00:00Z", "ms": ms, "world": {"id": "w1", "seed": 1, "tick": 5},
         "place": {"id": pid, "box": list(box), "pred": labels.CLASSES[pred_i], "pred_i": pred_i, "conf": 0.8},
         "model": {"weights": "none"}, "answer": answer, "label_i": label_i,
         "label": labels.CLASSES[label_i] if label_i >= 0 else None, "comment": ""}
    if with_cube:
        r["cube"] = {"origin": list(origin), "size": 32, "channels": 16, "order": "x,y,z", "enc": "u8-deflate-base64", "source": "world", "data": packed(cube())}
    return r


def action(ms, t, rating, reason):
    return {"v": 1, "kind": "action", "ms": ms, "world": {"id": "w1"}, "rating": rating, "reason": reason, "comment": "",
            "target": {"type": "case", "t": t, "agent": "Агент 1", "reflex": "stuck-jump", "reaction": "jump", "result": "OK", "ticks": 4, "at": [1, 64, 2]}}


def write(path, recs):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as h:
        for r in recs:
            h.write(json.dumps(r, ensure_ascii=False) + "\n")


class Places(unittest.TestCase):
    def setUp(self):
        self.d = tmp(self)
        write(os.path.join(self.d, "aiguide-labels", "places.jsonl"), [
            place(1, "p1", "relabel", 2),          # first a passage...
            place(2, "p1", "correct", 3),          # ...then the person said it is right: the last answer wins
            place(3, "p2", "not_place", -1, box=(0, 0, 0, 31, 9, 31), pred_i=4),
            place(4, "p3", "relabel", 7, box=(0, 10, 0, 3, 10, 3)),
            place(5, "p4", "correct", 3, with_cube=False),
        ])

    def test_set(self):
        out = os.path.join(self.d, "p.npz")
        self.assertEqual(labels.main(["places", self.d, "--out", out]), 0)
        z = np.load(out)
        # p2 (not a cave hall, its box all stone: no cell a cave hall labels) and p4 (no cube) are left out
        self.assertEqual(z["x"].shape, (2, 32, 32, 32))
        self.assertEqual(list(z["place_id"]), ["p1", "p3"])
        self.assertEqual(list(z["answer"]), ["correct", "relabel"])
        # p1: a room on the air of its box (6 x 4 x 6), the person's weight
        self.assertEqual(int((z["mask"][0] == 1).sum()), 6 * 4 * 6)
        self.assertTrue((z["y"][0][z["mask"][0] == 1] == 3).all())
        self.assertTrue((z["weight"][0][z["mask"][0] == 1] == 4.0).all() and (z["weight"][0][z["mask"][0] == 0] == 0).all())
        self.assertTrue((z["y"][0][z["mask"][0] == 0] == -1).all())
        # p3: a built structure on the built floor cells of its box (4 x 1 x 4)
        self.assertEqual(int((z["mask"][1] == 1).sum()), 16)
        self.assertTrue((z["y"][1][z["mask"][1] == 1] == 7).all())
        self.assertEqual(list(z["origin"][0]), [0, 0, 0])
        self.assertEqual(list(z["box"][1]), [0, 10, 0, 3, 10, 3])

    def test_onehot(self):
        out = os.path.join(self.d, "o.npz")
        labels.main(["places", self.d, "--out", out, "--onehot", "--strong", "2"])
        z = np.load(out)
        self.assertEqual(z["x"].shape[1:], (16, 32, 32, 32))
        self.assertTrue((z["x"].sum(axis=1) == 1).all())
        self.assertEqual(float(z["weight"].max()), 2.0)

    def test_zip(self):
        zp = os.path.join(self.d, "share.zip")
        with zipfile.ZipFile(zp, "w") as zf:
            zf.write(os.path.join(self.d, "aiguide-labels", "places.jsonl"), "labels/places.jsonl")
        self.assertEqual(len(labels.read([zp], "places.jsonl")), 5)


class NotPlace(unittest.TestCase):
    def test_negative(self):
        d = tmp(self)
        write(os.path.join(d, "places.jsonl"), [place(1, "p9", "not_place", -1, box=(0, 11, 0, 7, 20, 7), pred_i=3)])
        data, skipped = labels.places_set(labels.read([os.path.join(d, "places.jsonl")], "places.jsonl"))
        self.assertEqual(int((data["mask"][0] == 2).sum()), 8 * 10 * 8)
        self.assertEqual(int(data["label"][0]), -1)
        self.assertTrue((data["y"][0][data["mask"][0] == 2] == 3).all())

    def test_nothing_takes_it(self):
        d = tmp(self)
        write(os.path.join(d, "places.jsonl"), [place(1, "p8", "relabel", 4, box=(0, 0, 0, 31, 9, 31))])   # a cave hall in solid stone
        data, skipped = labels.places_set(labels.read([os.path.join(d, "places.jsonl")], "places.jsonl"))
        self.assertIsNone(data)
        self.assertEqual(sum(skipped.values()), 1)


class Actions(unittest.TestCase):
    def test_table(self):
        d = tmp(self)
        write(os.path.join(d, "aiguide-labels", "actions.jsonl"), [
            action(1, 100, 1, "fast"), action(2, 100, -1, "dumb"), action(3, 200, -1, "dangerous"),
            {"v": 1, "kind": "action", "ms": 4, "rating": 1, "reason": "right", "target": {"type": "card", "ms": 99, "agent": "Агент 2", "card": "chop#3", "result": "OK"}},
        ])
        write(os.path.join(d, "crew-cases.jsonl"), [
            {"t": 100, "agent": "Агент 1", "reflex": "stuck-jump", "reaction": "jump", "result": "OK", "escalated": None},
            {"t": 200, "agent": "Агент 1", "reflex": "stuck-jump", "reaction": "jump", "result": "NO_PATH", "escalated": "task"},
        ])
        out = os.path.join(d, "r.csv")
        self.assertEqual(labels.main(["actions", d, "--cases", d, "--out", out]), 0)
        rows = labels.read_table(out)
        self.assertEqual(len(rows), 3)
        a, b, c = rows
        self.assertEqual((a["t"], a["rating"], a["n"], a["auto_ok"], a["in_journal"]), ("100", "0.0", "2", "1", "1"))
        self.assertEqual((b["rating"], b["auto_ok"], b["reasons"]), ("-1.0", "0", "dangerous:1"))
        self.assertEqual((c["type"], c["card"], c["rating"]), ("card", "chop#3", "1.0"))
        rep = cases.ratings([out])
        g = rep["by_reaction"]["stuck-jump / jump"]
        self.assertEqual((g["rated"], g["good"], g["bad"], g["disagree"]), (2, 0, 2, 1))   # t 100: 0.0 is not good, the journal says OK

    def test_jsonl_out(self):
        d = tmp(self)
        write(os.path.join(d, "actions.jsonl"), [action(1, 7, 1, None)])
        out = os.path.join(d, "r.jsonl")
        labels.main(["actions", os.path.join(d, "actions.jsonl"), "--out", out])
        rows = labels.read_table(out)
        self.assertEqual(rows[0]["rating"], 1.0)
        with open(out, "rb") as h:
            self.assertNotIn(b"\r", h.read())


class FromJava(unittest.TestCase):
    """the lines Labels.java wrote in FeedbackTest read here as the model's pipeline will read them"""
    def test_java_records(self):
        p = os.path.join(ROOT, "build", "test-tmp", "labels", "aiguide-labels", "places.jsonl")
        if not os.path.isfile(p):
            self.skipTest("run tools/test_client.py (FeedbackTest writes the file)")
        data, skipped = labels.places_set(labels.read([p], "places.jsonl"))
        self.assertEqual(len(data["pred"]), 1)          # two answers for one place: the later one
        self.assertEqual(int(data["label"][0]), 3)
        self.assertTrue((data["x"][0][:, :10, :] == 5).all() and (data["x"][0][:, 10, :] == 9).all() and (data["x"][0][:, 11:, :] == 0).all())
        self.assertEqual(int((data["mask"][0] == 1).sum()), 6 * 4 * 6)


if __name__ == "__main__":
    unittest.main()
