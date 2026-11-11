"""Training progress to the owner's Telegram Saved Messages, via the telegram-mcp server (tunnel 127.0.0.1:3789).
Run by the scheduled task 'aiguide-rl-progress' every 30 min; needs no Claude session."""
import csv, glob, json, os, sys, time, urllib.request
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOTS = [r"C:\develop\games\minecraft\aiguide-sim-batch\build\rl\runs",
         r"C:\develop\games\minecraft\aiguide-engine-sim\build\rl\runs",
         r"C:\develop\games\minecraft\aiguide-open-world\build\rl\runs"]
URL = "http://127.0.0.1:3789/mcp"
SCEN = ["wood", "pickaxe", "sword", "iron", "farm", "waves:easy", "waves:normal", "waves:long", "fortify", "night"]


def runs(max_age_h=0.5):
    out = []
    for root in ROOTS:
        for m in glob.glob(os.path.join(root, "*", "metrics.csv")):
            age = (time.time() - os.path.getmtime(m)) / 3600
            if age <= max_age_h:
                out.append((m, age))
    return sorted(out, key=lambda x: x[1])


def summary(path, age_h):
    rows = list(csv.DictReader(open(path, encoding="utf8")))
    if not rows:
        return None
    r = rows[-1]
    name = os.path.basename(os.path.dirname(path))
    where = "движок" if "engine-sim" in path else ("открытый мир" if "open-world" in path else "симулятор")
    status = "идёт" if age_h < 0.2 else f"стоит {age_h*60:.0f} мин"
    lines = [f"▶ {name} ({where}, {status})",
             f"эпоха {r.get('epoch')}, {float(r.get('minutes', 0)):.0f} мин, решений {int(float(r.get('decisions', 0))):,}".replace(",", " "),
             f"этап: {r.get('stage')}, сдаётся: {float(r.get('noop_end_frac') or 0)*100:.1f} %"]
    succ = []
    for k in SCEN:
        vals = [(float(x["succ_" + k]), int(x["n_" + k])) for x in rows[-10:] if x.get("succ_" + k)]
        n = sum(b for _, b in vals)
        if n:
            succ.append(f"{k} {sum(a*b for a, b in vals)/n*100:.0f}%")
    if succ:
        lines.append("успех: " + ", ".join(succ))
    cl = os.path.join(os.path.dirname(path), "curriculum.log")
    if os.path.exists(cl):
        last = open(cl, encoding="utf8").read().strip().splitlines()[-1:]
        if last:
            lines.append("последнее: " + last[0])
    return "\n".join(lines)


def rpc(sid, body):
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if sid:
        h["Mcp-Session-Id"] = sid
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=60) as f:
        return f.headers.get("Mcp-Session-Id") or sid, f.read().decode("utf8", "replace")


def send(text):
    sid, _ = rpc(None, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                        "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                                   "clientInfo": {"name": "aiguide-progress", "version": "1"}}})
    rpc(sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    _, out = rpc(sid, {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                       "params": {"name": "send_message", "arguments": {"chat_id": "me", "message": text}}})
    return out


if __name__ == "__main__":
    parts = [s for s in (summary(p, a) for p, a in runs()) if s]
    text = "🤖 AI Guide — обучение\n\n" + ("\n\n".join(parts) if parts else "Активных прогонов за 30 мин нет (обучение стоит или ждёт).")
    print(text)
    print(send(text)[:300])
