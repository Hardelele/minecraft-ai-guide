"""Reads a WorldRec session.bin.gz (the format of WorldRec.java / Replay.load) for acceptance checks.

    python worldrec_read.py <session.bin.gz> door --door -144 69 178 --site -146 69 179 --size 2

Facts only from the recording: agents' positions by tick, block changes, door metadata. Exit 0 only when the
acceptance holds: every agent stands on the interior floor, the door's lower half goes open -> closed after
the last entrant, and no block of the house other than the door changed.
"""
import argparse, gzip, json, struct, sys


class Rec:
    def __init__(self, path):
        self.strings, self.ticks = [], []
        self.b = gzip.open(path, "rb").read()
        self.p = 0
        assert self.i() == 0x41495245, "not a recording"
        self.i()
        cur = None
        while self.p < len(self.b):
            r = self.u(">b")
            if r == 0:
                continue
            if r == 6:
                i = self.u(">h"); s = self.utf()
                self.strings += [None] * (i + 1 - len(self.strings))
                self.strings[i] = s
            elif r == 1:
                cur = {"tick": self.i(), "time": self.u(">q"), "rain": self.u(">?"), "ents": {}, "blocks": [], "breaks": [], "sounds": []}
                self.ticks.append(cur)
            elif r == 2:
                e = {"id": self.i(), "type": self.u(">h"), "x": self.u(">d"), "y": self.u(">d"), "z": self.u(">d"),
                     "yaw": self.u(">f"), "pitch": self.u(">f"), "head": self.u(">f"), "flags": self.u(">b"),
                     "health": self.u(">f"), "hurt": self.u(">b"), "item": self.u(">h")}
                if e["item"] >= 0:
                    e["meta"] = self.u(">h"); e["count"] = self.u(">b")
                if self.strings[e["type"]] == "agent":
                    e["name"] = self.strings[self.u(">h")]; e["act"] = self.u(">b")
                    e["ax"] = self.i(); e["ay"] = self.u(">h"); e["az"] = self.i()
                cur["ents"][e["id"]] = e
            elif r == 3:
                cur["blocks"].append((self.i(), self.u(">h"), self.i(), self.u(">h"), self.u(">B")))
            elif r == 4:
                cur["breaks"].append((self.i(), self.i(), self.u(">h"), self.i(), self.u(">b")))
            elif r == 5:
                cur["sounds"].append((self.u(">h"), self.u(">f"), self.u(">f"), self.u(">f"), self.u(">f"), self.u(">f")))
            else:
                raise ValueError("record %d at %d" % (r, self.p))

    def u(self, f):
        n = struct.calcsize(f); v = struct.unpack_from(f, self.b, self.p)[0]; self.p += n; return v

    def i(self):
        return self.u(">i")

    def utf(self):
        n = self.u(">H"); s = self.b[self.p:self.p + n].decode("utf-8", "replace"); self.p += n; return s

    def agents(self, t):
        return {e["name"]: e for e in t["ents"].values() if "name" in e}


def door(a):
    rec = Rec(a.session)
    dx, dy, dz = a.door
    half = 2 if a.size == 0 else 3
    x0, z0 = a.site[0] + 2 - half, a.site[2] + 2 - half
    inside = lambda e: x0 < int(e["x"] // 1) < x0 + 2 * half and z0 < int(e["z"] // 1) < z0 + 2 * half and a.site[1] - 0.2 <= e["y"] < a.site[1] + 0.9
    first_in, door_events, house, breaks = {}, [], [], []
    for n, t in enumerate(rec.ticks):
        for name, e in rec.agents(t).items():
            if inside(e) and name not in first_in:
                first_in[name] = n
        for b in t["blocks"]:
            if (b[0], b[1], b[2]) == (dx, dy, dz):
                door_events.append({"tick": n, "time": t["time"], "id": b[3], "meta": b[4]})
            elif x0 <= b[0] <= x0 + 2 * half and z0 - 1 <= b[2] <= z0 + 2 * half and a.site[1] <= b[1] <= a.site[1] + 5:   # the ground under it (grass to dirt) is not the house
                house.append({"tick": n, "block": b})
        breaks += [{"tick": n, "at": b[1:4], "stage": b[4]} for b in t["breaks"]]
    last = rec.ticks[-1]
    final = {k: [round(e["x"], 2), round(e["y"], 2), round(e["z"], 2), inside(e)] for k, e in sorted(rec.agents(last).items())}
    closed = [d for d in door_events if d["id"] == 64 and not d["meta"] & 4]
    out = {"ticks": len(rec.ticks), "time_first": rec.ticks[0]["time"], "time_last": last["time"], "first_inside_tick": first_in,
           "door_events": door_events, "house_changes": house, "break_records": len(breaks), "final": final}
    entered = len(first_in) == 4
    closed_after = bool(closed) and entered and closed[0]["tick"] >= max(first_in.values())
    stays = all(v[3] for v in final.values())
    out["accept"] = {"four_entered": entered, "closed_after_last_entrant": closed_after, "no_other_house_block_changed": not house, "all_inside_at_end": stays}
    print(json.dumps(out, ensure_ascii=False, indent=1))
    return 0 if all(out["accept"].values()) else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session"); ap.add_argument("check", choices=["door"])
    ap.add_argument("--door", type=int, nargs=3, required=True)
    ap.add_argument("--site", type=int, nargs=3, required=True)
    ap.add_argument("--size", type=int, default=2)
    return door(ap.parse_args())


if __name__ == "__main__":
    sys.exit(main())
