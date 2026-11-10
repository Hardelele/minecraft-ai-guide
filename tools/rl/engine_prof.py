"""Where the server thread's time goes: a Java Flight Recorder file (tools/rl/engine_env.py --jfr) read with the JDK's
`jfr` tool, its execution samples of the server's tick threads sorted into kinds of work by their stacks (method names
from SRG to MCP by lib/srg2mcp.srg). A sample is put to the first kind (in RULES' order) a frame of its stack belongs
to: the game's path finding before the mod's code that called it, the light before the block change that asked for it.

    python tools/rl/engine_prof.py build/rl/engine/prof-server-wood.jfr [--top 25] [--json out.json]
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
JDK = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
MOD = "hardelele.aiguide."

_names = None


def names():
    """SRG method name -> MCP name (func_72835_b -> tick)"""
    global _names
    if _names is None:
        _names = {}
        with open(os.path.join(ROOT, "lib", "srg2mcp.srg"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("MD: "):
                    p = line.split()
                    _names[p[1].rsplit("/", 1)[1]] = p[3].rsplit("/", 1)[1]
    return _names


def frame_name(fr):
    m = fr["method"]
    cls = m["type"]["name"].replace("/", ".")
    n = m["name"]
    return cls, names().get(n, n)


def has(st, pred):
    return any(pred(c, n) for c, n in st)


def wrapper(c, n):
    """RlEnv.tick runs the server's ticks back to back (fast): the game's own work under it is not the mod's"""
    return c.endswith("client.RlEnv") and n == "tick"


def mod_class(st):
    """the innermost of the mod's classes in the stack"""
    for c, n in st:
        if c.startswith(MOD) and not wrapper(c, n):
            return c[len(MOD):].replace("client.", "").split("$")[0]
    return None


PATH = ("net.minecraft.pathfinding.", "net.minecraft.entity.ai.EntityAIMoveTowards", "net.minecraft.entity.ai.EntityAIWander",
        "net.minecraft.entity.ai.EntityAIAttackOnCollide", "net.minecraft.entity.ai.RandomPositionGenerator")
LIGHT = {"updateLightByType", "computeLightValue", "updateAllLightTypes", "relightBlock", "recheckGaps", "generateSkylightMap",
         "func_150804_b", "propagateSkylightOcclusion", "checkSkylightNeighborHeight", "updateSkylightNeighborHeight", "func_150801_a",
         "func_150802_m", "func_150809_p", "enqueueRelightChecks", "func_76594_o", "func_98179_a", "func_147464_a", "func_147469_q", "func_147451_t",
         "func_147463_c", "getSavedLightValue", "getBlockLightValue_do"}
SAVE = {"saveAllChunks", "saveAllWorlds", "saveChunks", "saveChunkData", "writeChunkToNBT", "saveLevel", "writeExtraData", "func_71267_a",
        "saveChunkExtraData", "flush"}
CHUNKS = ("net.minecraft.world.gen.", "net.minecraft.world.chunk.storage.AnvilChunkLoader", "net.minecraft.world.chunk.storage.RegionFile",
          "net.minecraft.world.biome.")
NET = ("net.minecraft.network.", "io.netty.", "net.minecraft.server.network.")
TRACK = ("net.minecraft.entity.EntityTracker", "net.minecraft.entity.EntityTrackerEntry", "net.minecraft.server.management.PlayerManager")
PHYS = {"moveEntity", "getCollidingBoundingBoxes", "func_147461_a", "handleWaterMovement", "handleLavaMovement", "isInsideOfMaterial",
        "func_145775_I", "doBlockCollisions", "collideWithNearbyEntities", "getEntitiesWithinAABBExcludingEntity", "selectEntitiesWithinAABB",
        "getEntitiesWithinAABB", "func_72945_a", "isOffsetPositionInLiquid"}


def kind(st):
    """st: [(class, method)] from the top (running) frame down"""
    env = has(st, lambda c, n: c.endswith("client.RlEnv") and n in ("answer", "facts", "mask", "vector"))
    envm = has(st, lambda c, n: c.endswith("client.RlEnv") and n in ("measure", "progress", "episodeTick", "goal", "labels"))
    reset = has(st, lambda c, n: (c.endswith("client.RlEnv") and n in ("reset", "clearCrew")) or c.endswith("client.RlArena"))
    if reset:
        return "среда: сброс арены"
    if has(st, lambda c, n: c.endswith("WorldServerMulti")):
        return "другие измерения (Незер, Энд)"
    if has(st, lambda c, n: c.startswith(PATH)):
        if env:
            return "поиск пути: для фактов среды"
        if has(st, lambda c, n: c.startswith(MOD) and not wrapper(c, n)):
            return "поиск пути: агенты (навыки, рефлексы)"
        return "поиск пути: мобы и животные (ИИ ванильный)"
    if has(st, lambda c, n: c.startswith("net.minecraft.") and n in LIGHT):
        return "свет: пересчёт"
    if has(st, lambda c, n: n in SAVE and c.startswith("net.minecraft.")) or has(st, lambda c, n: c.startswith("net.minecraft.world.storage.ThreadedFileIOBase")):
        return "сохранение"
    if has(st, lambda c, n: c.startswith(CHUNKS) and not c.endswith("ChunkProviderServer")) or \
            has(st, lambda c, n: c.endswith("ChunkProviderServer") and n in ("loadChunk", "unloadQueuedChunks", "originalLoadChunk", "populate")):
        return "чанки: загрузка/выгрузка/генерация"
    if has(st, lambda c, n: c.startswith(TRACK)):
        return "трекинг сущностей для клиентов"
    if has(st, lambda c, n: c.startswith(NET)):
        return "сеть MC (NetworkSystem)"
    if has(st, lambda c, n: c.endswith("client.RlEnv$Conn") or c.startswith("com.google.gson.")):
        return "среда: протокол (JSON, сокет)"
    if env:
        return "среда: наблюдение и маска (RlEnv.facts)"
    if envm:
        return "среда: измерения награды (RlEnv.measure)"
    if has(st, lambda c, n: c.startswith("net.minecraft.") and n in PHYS):
        return "сущности: физика и столкновения"
    m = mod_class(st)
    if m:
        return "мод: " + m
    if has(st, lambda c, n: c.startswith("net.minecraft.entity.")):
        return "сущности: ИИ и обновление (ванильные)"
    if has(st, lambda c, n: c.endswith("WorldServer") and n in ("tickUpdates", "func_147456_g", "tickBlocksAndAmbiance", "func_147486_a")):
        return "блоки: тики (жидкости, огонь, рост)"
    if has(st, lambda c, n: c.startswith("net.minecraft.")):
        return "мир: прочее"
    return "прочее (JVM, FML)"


MOD_GROUPS = {
    "тело и навыки агентов": ("Body", "Agent", "Moves", "Fell", "Reach", "Paths", "PrimSkills", "Skills", "MindSkills", "MineSkills", "FarmSkills",
                               "GearSkills", "DefenseSkills", "RlSkills", "Day1", "Day2", "Ground", "Routines", "Hunger", "AgentLife", "Doer", "Pilot"),
    "рефлексы": ("Reacts", "Reflexes", "Reflex", "Guard"),
    "разум (Mind, AgentMind, PolicyMind)": ("Mind", "AgentMind", "PolicyMind", "MindState", "MindShare", "Planner"),
    "экипаж": ("Crew", "Orders", "TaskBoard", "Commander", "Director", "Errands", "Assistant", "Ledger", "Events", "Roles"),
    "восприятие (Exploration, Sight, Fog)": ("Exploration", "Sight", "Fog", "FogMap", "Prospecting", "Situation", "Labels"),
    "волны и оборона": ("Waves", "WaveMobs", "WaveCycle", "WavePlan", "Defense", "DefenseWorld", "WaveRing", "Ghosts"),
    "причины (RlCause)": ("RlCause", "RlCauseHooks"),
}


def group(k):
    if not k.startswith("мод: "):
        return k
    c = k[5:]
    for g, cs in MOD_GROUPS.items():
        if c in cs:
            return "мод: " + g
    return "мод: прочее (" + c + ")"


JMC = os.path.join(ROOT, "build", "jmc")   # JMC's parser: flightrecorder, common, encoder jars (Maven Central, 8.3.1)
DUMP_OUT = os.path.join(ROOT, "build", "jfr-dump")


def dump(path):
    """the samples of a recording as text by tools/rl/engine/JfrDump.java (reads Oracle 8's format 0.9 too)"""
    txt = path[:-4] + ".samples.txt"
    if os.path.isfile(txt) and os.path.getmtime(txt) >= os.path.getmtime(path):
        return txt
    cp = os.pathsep.join([os.path.join(JMC, "*"), DUMP_OUT])
    if not os.path.isfile(os.path.join(DUMP_OUT, "JfrDump.class")):
        os.makedirs(DUMP_OUT, exist_ok=True)
        subprocess.check_call([os.path.join(JDK, "bin", "javac"), "-nowarn", "-cp", os.path.join(JMC, "*"), "-d", DUMP_OUT,
                               os.path.join(HERE, "engine", "JfrDump.java")])
    subprocess.check_call([os.path.join(JDK, "bin", "java"), "-Xmx4G", "-cp", cp, "JfrDump", path, txt])
    return txt


def mcp(cls, meth):
    return cls, names().get(meth, meth)


def read(path, threads):
    out = []
    with open(dump(path), encoding="utf-8") as f:
        for line in f:
            name, _, frames = line.rstrip("\n").partition("\t")
            if threads and not any(name.startswith(x) for x in threads):
                continue
            st = [mcp(*fr.rsplit(".", 1)) for fr in frames.split(";") if "." in fr]
            out.append((name, st))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jfr", nargs="+")
    ap.add_argument("--threads", default="Server thread,Arena", help="sampled threads by name prefix (comma separated)")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--json", default="")
    a = ap.parse_args()
    report = {}
    for path in a.jfr:
        smp = read(path, [x for x in a.threads.split(",") if x])
        kinds, groups, tops, callers = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
        for _, st in smp:
            k = kind(st)
            kinds[k] += 1
            groups[group(k)] += 1
            if st:
                tops["%s.%s" % st[0]] += 1
            # the mod's (and RlEnv's) methods the time is under: the innermost two of the mod's frames
            mine = [(c[len(MOD):].replace("client.", ""), n) for c, n in st if c.startswith(MOD) and not wrapper(c, n)]
            if mine:
                callers[k + " <- " + " <- ".join("%s.%s" % x for x in mine[:2])] += 1
        n = max(1, len(smp))
        print("\n== %s: %d samples of the tick thread(s)" % (os.path.basename(path), len(smp)))
        for k, v in groups.most_common():
            print("  %5.1f %%  %s" % (100.0 * v / n, k))
        print("  -- the mod by class:")
        for k, v in kinds.most_common():
            if k.startswith("мод: "):
                print("  %5.1f %%  %s" % (100.0 * v / n, k))
        print("  -- top frames:")
        for k, v in tops.most_common(a.top):
            print("  %5.1f %%  %s" % (100.0 * v / n, k))
        print("  -- under the mod's methods:")
        for k, v in callers.most_common(a.top):
            print("  %5.1f %%  %s" % (100.0 * v / n, k))
        report[os.path.basename(path)] = {"samples": len(smp), "groups": {k: round(100.0 * v / n, 2) for k, v in groups.most_common()},
                                          "kinds": {k: round(100.0 * v / n, 2) for k, v in kinds.most_common()},
                                          "top": {k: round(100.0 * v / n, 2) for k, v in tops.most_common(a.top)},
                                          "callers": {k: round(100.0 * v / n, 2) for k, v in callers.most_common(a.top)}}
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
