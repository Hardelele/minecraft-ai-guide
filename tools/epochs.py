"""Runs a run of epochs on the headless bench (ARCHITECTURE-LAYERS.md, "Эпохи обучения"): one server start a epoch, the
world put back from its copy before each, until the run is through (learning and control in turn: the mod decides
which comes next from epochs/<run>/state.json).

  python tools/epochs.py --server C:\\bench --run r1 --epochs 10 --level normal --minutes 20 [--control on|off]
         [--seeds same|grow] [--seed N] [--learn cases,lessons,knobs] [--crew 4] [--model stub] [--rec]
         [--jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar] [--java java] [--xmx 2G] [--dry-run] [-- more JVM args]

The server folder has the Forge jar, mods/ (AIGuideClient and the server AIGuide with its key for the minds and the
lessons) and the world (server.properties' level-name, "world"). Before the first epoch the world is copied to
epochs/<run>/world-base (the server must not be running); before every epoch the world folder is replaced by that
copy and marked fresh (world/aiguide-epoch.json): the mod writes in the report whether each epoch began from it."""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time


def level_name(server):
    p = os.path.join(server, "server.properties")
    if os.path.isfile(p):
        with open(p, encoding="utf-8", errors="replace") as h:
            for line in h:
                if line.startswith("level-name="):
                    return line.split("=", 1)[1].strip() or "world"
    return "world"


def copy_world(src, dst):
    """a world folder copied whole, its session.lock left out"""
    shutil.copytree(src, dst, ignore=shutil.ignore_patterns("session.lock"))


def base_copy(server, run):
    """epochs/<run>/world-base: made once from the world as it is now; its path"""
    base = os.path.join(server, "epochs", run, "world-base")
    if not os.path.isdir(base):
        world = os.path.join(server, level_name(server))
        if not os.path.isdir(world):
            raise SystemExit("no world at " + world + " to copy")
        os.makedirs(os.path.dirname(base), exist_ok=True)
        copy_world(world, base)
    return base


def restore(server, run, epoch_hint=""):
    """the world replaced by the run's copy and marked fresh (the mod marks it used when the epoch begins)"""
    base = base_copy(server, run)
    world = os.path.join(server, level_name(server))
    if os.path.isdir(world):
        shutil.rmtree(world)
    copy_world(base, world)
    with open(os.path.join(world, "aiguide-epoch.json"), "w", encoding="utf-8") as h:
        json.dump({"run": run, "from": "world-base", "restored": time.strftime("%Y-%m-%dT%H:%M:%S"), "next": epoch_hint, "used": False}, h)
    return world


def state(server, run):
    p = os.path.join(server, "epochs", run, "state.json")
    if not os.path.isfile(p):
        return {}
    with open(p, encoding="utf-8") as h:
        return json.load(h)


def done_count(st):
    return st.get("learn", {}).get("done", 0) + st.get("control", {}).get("done", 0)


def finished(st, epochs, control):
    return st.get("learn", {}).get("done", 0) >= epochs and (not control or st.get("control", {}).get("done", 0) >= epochs)


def command(a, extra):
    """the java command of one epoch"""
    props = {
        "aiguide.bench": "epochs:" + a.level,
        "aiguide.bench.out": os.path.join("epochs", a.run, "bench-last.json"),
        "aiguide.epochs.run": a.run,
        "aiguide.epochs.count": a.epochs,
        "aiguide.epochs.minutes": a.minutes,
        "aiguide.epochs.control": a.control,
        "aiguide.epochs.seeds": a.seeds,
        "aiguide.epochs.learn": a.learn,
        "aiguide.bench.waves.crew": a.crew,
        "aiguide.bench.waves.guard": "mind",
    }
    if a.seed:
        props["aiguide.epochs.seed"] = a.seed
    if a.model:
        props["aiguide.epochs.model"] = a.model
    if a.rec:
        props["aiguide.epochs.rec"] = "true"
    cmd = [a.java, "-Xmx" + a.xmx, "-Dfile.encoding=UTF-8"] + ["-D%s=%s" % (k, v) for k, v in props.items()] + list(extra)
    return cmd + ["-jar", a.jar, "nogui"]


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    extra = []
    if "--" in argv:
        i = argv.index("--")
        argv, extra = argv[:i], argv[i + 1:]
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--server", required=True)
    ap.add_argument("--run", default="epochs")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--level", default="normal")
    ap.add_argument("--minutes", type=int, default=20)
    ap.add_argument("--control", default="on", choices=["on", "off"])
    ap.add_argument("--seeds", default="same", choices=["same", "grow"])
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--learn", default="cases,lessons,knobs")
    ap.add_argument("--crew", type=int, default=4)
    ap.add_argument("--model", default="", help="'stub': the lessons by the stand-in (no network); empty: the server AIGuide's model")
    ap.add_argument("--rec", action="store_true", help="each epoch recorded (WorldRec) for filming")
    ap.add_argument("--jar", default="forge-1.7.10-10.13.4.1614-1.7.10-universal.jar")
    ap.add_argument("--java", default="java")
    ap.add_argument("--xmx", default="2G")
    ap.add_argument("--dry-run", action="store_true", help="put the world back and print the command, start nothing")
    a = ap.parse_args(argv)
    server = os.path.abspath(a.server)
    control = a.control == "on"
    total = a.epochs * (2 if control else 1)
    stuck = 0
    for _ in range(total + 2):
        st = state(server, a.run)
        if finished(st, a.epochs, control):
            print("epochs: the run %s is through (%d epochs): %s" % (a.run, done_count(st), os.path.join(server, "epochs", a.run, "report.json")))
            return 0
        restore(server, a.run, str(done_count(st) + 1))
        cmd = command(a, extra)
        print("epochs: epoch %d of %d, world put back; %s" % (done_count(st) + 1, total, " ".join(cmd)), flush=True)
        if a.dry_run:
            return 0
        r = subprocess.call(cmd, cwd=server)
        after = state(server, a.run)
        if done_count(after) <= done_count(st):
            stuck += 1
            print("epochs: the server ended (%d) and no epoch was closed (%d in a row)" % (r, stuck), flush=True)
            if stuck >= 2:
                return 1
        else:
            stuck = 0
    print("epochs: stopped after %d starts" % (total + 2))
    return 1


if __name__ == "__main__":
    sys.exit(main())
