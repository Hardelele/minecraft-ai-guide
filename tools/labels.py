"""The person's ratings (aiguide-labels/places.jsonl, actions.jsonl in a world, or inside share archives) made into
training sets. Nothing goes over the network.

    python tools/labels.py places  <world | aiguide-labels | places.jsonl | share.zip> [...] --out places-labels.npz
    python tools/labels.py actions <world | aiguide-labels | actions.jsonl | share.zip> [...] --out case-ratings.csv
    python tools/labels.py summary <...>

places: one sample per rated place (the last answer for a place wins), its 32^3 input cube from the record
(places/CONTRACT.md's 16 channels) and the person's label on the cells of its box:
    x       uint8  [N,32,32,32]   channel index per cell, [x][y][z] (--onehot: uint8 [N,16,32,32,32], NCDHW)
    y       int8   [N,32,32,32]   class per cell (contract's 0..7), -1 none
    mask    uint8  [N,32,32,32]   0 no label; 1 the cell is y; 2 the cell is NOT y (the person said "not a place")
    weight  float32[N,32,32,32]   --strong (default 4.0) on labelled cells, 0 elsewhere: a person's label outweighs the
                                  weak automatic ones (weight 1 in the model's pipeline)
    origin int32 [N,3], box int32 [N,6], pred int8 [N], label int8 [N], answer, place_id, world_id, weights, cube_source
Which cells of the box get the label: an air class (open_outside, passage, room, cave_hall, shaft, junction) on its
air, climb and light cells; built_structure on built_solid, door, fence and storage cells; solid on all seen non-air
cells. Unseen cells never.

actions: a table case -> rating for tools/cases.py (--ratings): one row per rated episode (a reflex's case by its
tick, agent and reflex; a finished card by its line), the mean of its ratings (+1/-1), the reasons, how many, and
whether the case is in the journals given (--cases) with its automatic result.
"""
import argparse
import base64
import collections
import csv
import json
import os
import sys
import zipfile
import zlib

CLASSES = ["solid", "open_outside", "passage", "room", "cave_hall", "shaft", "junction", "built_structure"]
SIDE = 32
AIR_CLASSES = {1, 2, 3, 4, 5, 6}
AIR_CHANNELS = {0, 12, 13}          # air, climb, light
BUILT_CHANNELS = {9, 10, 11, 14}    # built_solid, door, fence, storage
UNSEEN = 1


# ---- reading ---------------------------------------------------------------------------------------------------

def _lines(data, name):
    out, bad = [], 0
    for line in data.decode("utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
            if isinstance(o, dict):
                out.append(o)
            else:
                bad += 1
        except ValueError:
            bad += 1
    if bad:
        print(f"{name}: {bad} bad lines skipped", file=sys.stderr)
    return out


def read(paths, which):
    """the records of 'places.jsonl' or 'actions.jsonl' (which) from worlds, label folders, files and share zips"""
    recs = []
    for p in paths:
        if os.path.isfile(p) and zipfile.is_zipfile(p):
            with zipfile.ZipFile(p) as z:
                for n in z.namelist():
                    if n == "labels/" + which or n.endswith("/labels/" + which):
                        recs += _lines(z.read(n), p + "!" + n)
        elif os.path.isfile(p):
            with open(p, "rb") as h:
                recs += _lines(h.read(), p)
        elif os.path.isdir(p):
            for d in (os.path.join(p, "aiguide-labels"), p):
                f = os.path.join(d, which)
                if os.path.isfile(f):
                    with open(f, "rb") as h:
                        recs += _lines(h.read(), f)
                    break
        else:
            print(f"{p}: not found", file=sys.stderr)
    return recs


def read_cases(paths):
    """the reflexes' cases from journals (a world folder, a crew-cases.jsonl) and share zips"""
    cases = []
    for p in paths:
        if os.path.isfile(p) and zipfile.is_zipfile(p):
            with zipfile.ZipFile(p) as z:
                names = sorted((n for n in z.namelist() if os.path.basename(n).startswith("crew-cases") and n.endswith(".jsonl")),
                               key=lambda n: (-_rot(n), n))
                for n in names:
                    cases += _lines(z.read(n), p + "!" + n)
        else:
            sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
            import cases as cases_tool  # noqa: E402  (the journal's own reader: rotated files, oldest first)
            got, _ = cases_tool.read_cases([p])
            cases += got
    return cases


def _rot(n):
    b = os.path.basename(n)[len("crew-cases"):-len(".jsonl")]
    return int(b[1:]) if b.startswith(".") and b[1:].isdigit() else 0


# ---- places ----------------------------------------------------------------------------------------------------

def unpack(cube):
    """a record's cube: channel per cell, [x][y][z]"""
    import numpy as np
    if cube.get("enc") != "u8-deflate-base64" or cube.get("size", SIDE) != SIDE:
        raise ValueError("unknown cube encoding")
    raw = zlib.decompress(base64.b64decode(cube["data"]))
    if len(raw) != SIDE ** 3:
        raise ValueError(f"a cube of {len(raw)} cells")
    return np.frombuffer(raw, dtype=np.uint8).reshape(SIDE, SIDE, SIDE)


def label_cells(x, origin, box, cls):
    """which cells of the cube (bool [32,32,32]) a label of this class over this box is about"""
    import numpy as np
    sel = np.zeros(x.shape, dtype=bool)
    lo = [max(0, box[i] - origin[i]) for i in range(3)]
    hi = [min(SIDE, box[i + 3] - origin[i] + 1) for i in range(3)]
    if any(h <= l for l, h in zip(lo, hi)):
        return sel
    sub = x[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]]
    if cls in AIR_CLASSES:
        m = np.isin(sub, list(AIR_CHANNELS))
    elif cls == 7:
        m = np.isin(sub, list(BUILT_CHANNELS))
    else:
        m = (sub != 0) & (sub != UNSEEN) & ~np.isin(sub, list(AIR_CHANNELS))
    sel[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]] = m
    return sel


def latest(recs):
    """the last answer for each place (by world, place id and cube origin), in time order"""
    by = collections.OrderedDict()
    for r in sorted(recs, key=lambda r: r.get("ms", 0)):
        if r.get("kind") != "place":
            continue
        pl = r.get("place") or {}
        cube = r.get("cube") or {}
        k = ((r.get("world") or {}).get("id"), pl.get("id"), tuple(cube.get("origin") or ()))
        by.pop(k, None)
        by[k] = r
    return list(by.values())


def places_set(recs, strong=4.0, onehot=False):
    import numpy as np
    rows, skipped = [], collections.Counter()
    for r in latest(recs):
        cube, pl = r.get("cube"), r.get("place") or {}
        if not cube:
            skipped["no cube"] += 1
            continue
        try:
            x = unpack(cube)
        except Exception:
            skipped["bad cube"] += 1
            continue
        origin, box = list(cube["origin"]), list(pl["box"])
        pred, ans = int(pl.get("pred_i", -1)), r.get("answer")
        y = np.full(x.shape, -1, dtype=np.int8)
        mask = np.zeros(x.shape, dtype=np.uint8)
        if ans in ("correct", "relabel"):
            lab = int(r.get("label_i", -1))
            if not 0 <= lab < len(CLASSES):
                skipped["no label"] += 1
                continue
            sel = label_cells(x, origin, box, lab)
            y[sel], mask[sel] = lab, 1
        elif ans == "not_place":
            lab = -1
            if not 0 <= pred < len(CLASSES):
                skipped["no prediction"] += 1
                continue
            sel = label_cells(x, origin, box, pred)
            y[sel], mask[sel] = pred, 2
        else:
            skipped["unknown answer"] += 1
            continue
        if not mask.any():
            skipped["no cell of the box takes the label"] += 1
            continue
        rows.append(dict(x=x, y=y, mask=mask, weight=np.where(mask > 0, np.float32(strong), np.float32(0)).astype(np.float32),
                         origin=origin, box=box, pred=pred, label=lab, answer=ans, place_id=str(pl.get("id")),
                         world_id=str((r.get("world") or {}).get("id")), weights=str((r.get("model") or {}).get("weights")),
                         cube_source=str(cube.get("source")), time=str(r.get("time"))))
    if not rows:
        return None, skipped
    x = np.stack([r["x"] for r in rows])
    if onehot:
        x = np.eye(16, dtype=np.uint8)[x].transpose(0, 4, 1, 2, 3)   # N, C, D=x, H=y, W=z
    out = dict(x=x, y=np.stack([r["y"] for r in rows]), mask=np.stack([r["mask"] for r in rows]), weight=np.stack([r["weight"] for r in rows]),
               origin=np.array([r["origin"] for r in rows], dtype=np.int32), box=np.array([r["box"] for r in rows], dtype=np.int32),
               pred=np.array([r["pred"] for r in rows], dtype=np.int8), label=np.array([r["label"] for r in rows], dtype=np.int8))
    for k in ("answer", "place_id", "world_id", "weights", "cube_source", "time"):
        out[k] = np.array([r[k] for r in rows])
    return out, skipped


# ---- actions ---------------------------------------------------------------------------------------------------

def case_key(t):
    if t.get("type") == "case":
        return ("case", int(t.get("t", -1)), t.get("agent", ""), t.get("reflex", ""))
    kind = "fight" if t.get("type") == "fight" else "card"   # a step of the defence is rated as a card is, by its line's time
    return (kind, int(t.get("ms", -1)), t.get("agent", ""), t.get("card", ""))


FIELDS = ["type", "t", "agent", "reflex", "reaction", "result", "ticks", "card", "ms", "rating", "n", "good", "bad",
          "reasons", "comments", "auto_ok", "in_journal"]


def actions_table(recs, cases=None):
    """one row per rated episode: the mean rating of it and the rest"""
    journal = {}
    for c in cases or []:
        journal[("case", int(c.get("t", -1)), c.get("agent", ""), c.get("reflex", ""))] = c
    rows = collections.OrderedDict()
    for r in sorted(recs, key=lambda r: r.get("ms", 0)):
        if r.get("kind") != "action" or not r.get("target"):
            continue
        t = r["target"]
        k = case_key(t)
        row = rows.get(k)
        if row is None:
            row = rows[k] = dict(type=k[0], t=t.get("t", ""), agent=t.get("agent", ""), reflex=t.get("reflex", ""),
                                 reaction=t.get("reaction", ""), result=t.get("result", ""), ticks=t.get("ticks", ""),
                                 card=t.get("card", ""), ms=t.get("ms", ""), ratings=[], reasons=collections.Counter(), comments=[])
        row["ratings"].append(1 if r.get("rating", 0) > 0 else -1)
        if r.get("reason"):
            row["reasons"][r["reason"]] += 1
        if r.get("comment"):
            row["comments"].append(r["comment"])
    out = []
    for k, row in rows.items():
        rs = row.pop("ratings")
        reasons, comments = row.pop("reasons"), row.pop("comments")
        row.update(rating=round(sum(rs) / len(rs), 3), n=len(rs), good=rs.count(1), bad=rs.count(-1),
                   reasons=";".join(f"{a}:{b}" for a, b in reasons.most_common()), comments=" | ".join(comments))
        if k[0] == "case":
            c = journal.get(k)
            row["in_journal"] = int(c is not None) if cases is not None else ""
            res = c.get("result") if c else row["result"]
            esc = c.get("escalated") if c else None
            row["auto_ok"] = int(res == "OK" and not esc)
        else:
            row["in_journal"], row["auto_ok"] = "", int(row["result"] == "OK")
        out.append(row)
    return out


def write_table(rows, path):
    if path.endswith(".jsonl"):
        with open(path, "w", encoding="utf-8", newline="\n") as h:
            for r in rows:
                h.write(json.dumps(r, ensure_ascii=False) + "\n")
        return
    with open(path, "w", encoding="utf-8", newline="") as h:
        w = csv.DictWriter(h, fieldnames=FIELDS, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in FIELDS})


def read_table(path):
    """a table written by 'actions' (csv or jsonl), or the ratings themselves (a labels folder, actions.jsonl, a zip)"""
    if os.path.isfile(path) and path.endswith(".csv"):
        with open(path, encoding="utf-8") as h:
            return list(csv.DictReader(h))
    if os.path.isfile(path) and path.endswith(".jsonl") and os.path.basename(path) != "actions.jsonl":
        with open(path, encoding="utf-8") as h:
            return [json.loads(l) for l in h if l.strip()]
    return actions_table(read([path], "actions.jsonl"))


# ---- the tool --------------------------------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["places", "actions", "summary"])
    ap.add_argument("inputs", nargs="+", help="a world folder, an aiguide-labels folder, a .jsonl, a share .zip")
    ap.add_argument("--out", help="places: .npz (default places-labels.npz); actions: .csv or .jsonl (default case-ratings.csv)")
    ap.add_argument("--strong", type=float, default=4.0, help="the weight of a person's label (default 4.0; weak labels: 1)")
    ap.add_argument("--onehot", action="store_true", help="places: x one-hot NCDHW instead of channel indexes")
    ap.add_argument("--cases", action="append", default=[], help="actions: journals to join (a world, crew-cases.jsonl, a zip)")
    a = ap.parse_args(argv)
    if a.what == "places":
        import numpy as np
        data, skipped = places_set(read(a.inputs, "places.jsonl"), a.strong, a.onehot)
        for k, n in skipped.items():
            print(f"skipped: {n} ({k})")
        if data is None:
            print("no samples")
            return 1
        out = a.out or "places-labels.npz"
        np.savez_compressed(out, **data)
        print(f"{out}: {len(data['pred'])} samples, {int((data['mask'] == 1).sum())} positive cells, {int((data['mask'] == 2).sum())} negative cells")
        return 0
    if a.what == "actions":
        journals = a.cases + [p for p in a.inputs if os.path.isfile(p) and zipfile.is_zipfile(p)]   # an archive has its journal in it
        rows = actions_table(read(a.inputs, "actions.jsonl"), read_cases(journals) if journals else None)
        out = a.out or "case-ratings.csv"
        write_table(rows, out)
        print(f"{out}: {len(rows)} rated episodes")
        return 0
    places, actions = read(a.inputs, "places.jsonl"), read(a.inputs, "actions.jsonl")
    ans = collections.Counter(r.get("answer") for r in latest(places))
    print(f"places: {len(places)} ratings, {len(latest(places))} places: " + ", ".join(f"{k} {v}" for k, v in ans.most_common()))
    by = collections.Counter((r.get("target") or {}).get("reflex") or (r.get("target") or {}).get("type") for r in actions)
    print(f"actions: {len(actions)} ratings: " + ", ".join(f"{k} {v}" for k, v in by.most_common()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
