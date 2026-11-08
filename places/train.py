"""Train the places net on real cubes (weak labels) and synthetic scenes (known labels); evaluate on held-out worlds,
held-out synthetic scenes and the "did it learn the wrong cue" sets.

    python places/train.py --minutes 30
    python places/train.py --eval-only build/places/ckpt/best.pt
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, str(Path(__file__).resolve().parent))
from model import C_IN, CLASSES, K, PlacesNet  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
B = ROOT / "build" / "places"
UNSEEN = 1
IGNORE = 255
VAL_WORLDS = ["ii-mir", "novyi1"]
# copies of one map (the same seed, the agents' runs): only two of them, so it does not dominate
SKIP_WORLDS = ["nb-door", "nb-film", "nb-mine"]


def load(path: Path):
    d = np.load(path)
    vis = np.unpackbits(d["vis"], axis=-1, count=32).astype(bool)
    return d["ch"], d["lab"], vis


def load_set(paths):
    parts = [load(p) for p in paths]
    return tuple(np.concatenate(x) for x in zip(*parts)) if parts else None


def to_gpu(s, dev):
    ch, lab, vis = s
    return torch.from_numpy(ch).to(dev), torch.from_numpy(lab).to(dev), torch.from_numpy(vis).to(dev)


def make_input(ch: torch.Tensor, lab: torch.Tensor, vis: torch.Tensor, mask: torch.Tensor):
    """ch, lab [n, 32, 32, 32] uint8, vis bool, mask [n] bool: which samples get the visibility mask."""
    m = mask[:, None, None, None] & ~vis
    ch = torch.where(m, torch.full_like(ch, UNSEEN), ch)
    lab = torch.where(m, torch.full_like(lab, IGNORE), lab)
    x = F.one_hot(ch.long(), C_IN).permute(0, 4, 1, 2, 3).float()
    return x, lab.long()


def augment(x: torch.Tensor, y: torch.Tensor, g: torch.Generator):
    """Rotations about Y and mirror images: x is NCDHW with D=x, H=y, W=z."""
    k = int(torch.randint(0, 4, (1,), generator=g))
    x = torch.rot90(x, k, (2, 4))
    y = torch.rot90(y, k, (1, 3))
    if torch.rand(1, generator=g) < 0.5:
        x = torch.flip(x, (2,))
        y = torch.flip(y, (1,))
    return x.contiguous(), y.contiguous()


@torch.no_grad()
def evaluate(net, s, dev, masked: bool, bs: int = 32):
    ch, lab, vis = s
    conf = torch.zeros(K, K, dtype=torch.long, device=dev)
    for i in range(0, len(ch), bs):
        c, l, v = (torch.from_numpy(a[i:i + bs]).to(dev) for a in (ch, lab, vis))
        x, y = make_input(c, l, v, torch.full((len(c),), masked, device=dev))
        with torch.autocast("cuda", dtype=torch.bfloat16):
            p = net(x).argmax(1)
        ok = y != IGNORE
        conf += torch.bincount(y[ok] * K + p[ok], minlength=K * K).view(K, K)
    conf = conf.cpu().numpy()
    inter = np.diag(conf)
    union = conf.sum(0) + conf.sum(1) - inter
    iou = np.where(union > 0, inter / np.maximum(union, 1), np.nan)
    return iou, conf


def report(name, iou, conf):
    present = conf.sum(1) > 0
    line = f"{name:28s} mIoU {np.nanmean(iou[present]):.3f} | " + " ".join(
        f"{c[:6]}={iou[i]:.2f}" if present[i] else f"{c[:6]}=--" for i, c in enumerate(CLASSES))
    print(line, flush=True)
    return {"miou": float(np.nanmean(iou[present])), "iou": {c: (None if not present[i] or np.isnan(iou[i]) else float(iou[i]))
                                                              for i, c in enumerate(CLASSES)},
            "conf": conf.tolist(), "support": conf.sum(1).tolist()}


def eval_all(net, sets, dev):
    net.eval()
    out = {}
    for name, s in sets.items():
        for masked in (False, True):
            iou, conf = evaluate(net, s, dev, masked)
            out[f"{name}{'/masked' if masked else ''}"] = report(f"{name}{'/masked' if masked else ''}", iou, conf)
    net.train()
    return out


def checkpoint(net, sets, dev, out, step, el, loss, best, hist):
    res = eval_all(net, {k: sets[k] for k in ("real-val", "synth-val")}, dev)
    score = (res["real-val/masked"]["miou"] + res["synth-val/masked"]["miou"]
             + res["real-val"]["miou"] + res["synth-val"]["miou"]) / 4
    hist.append({"step": step, "min": el / 60, "loss": loss, "score": score})
    torch.save({"model": net.state_dict(), "step": step}, out / "last.pt")
    if score > best:
        best = score
        torch.save({"model": net.state_dict(), "step": step}, out / "best.pt")
        print(f"  best {score:.3f} at step {step}", flush=True)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=30)
    ap.add_argument("--bs", type=int, default=16)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--synth-frac", type=float, default=0.5)
    ap.add_argument("--mask-frac", type=float, default=0.7)
    ap.add_argument("--eval-only")
    ap.add_argument("--out", default=str(B / "ckpt"))
    a = ap.parse_args()
    dev = "cuda"
    torch.manual_seed(0)
    g = torch.Generator().manual_seed(0)

    worlds = sorted((B / "real").glob("*.npz"))
    tr_real = [p for p in worlds if p.stem not in VAL_WORLDS and p.stem not in SKIP_WORLDS]
    va_real = [p for p in worlds if p.stem in VAL_WORLDS]
    print("train worlds:", [p.stem for p in tr_real], "val worlds:", [p.stem for p in va_real])
    sets = {"real-val": load_set(va_real), "synth-val": load_set([B / "synth" / "val.npz"])}
    for k in ("houses_unusual", "tunnels_bare", "decor"):
        p = B / "synth" / f"ood-{k}.npz"
        if p.exists():
            sets[f"ood-{k}"] = load_set([p])
    net = PlacesNet().to(dev)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if a.eval_only:
        net.load_state_dict(torch.load(a.eval_only, map_location=dev)["model"])
        res = eval_all(net, sets, dev)
        (out / "eval.json").write_text(json.dumps(res, indent=1))
        return

    R = to_gpu(load_set(tr_real), dev)
    Sy = to_gpu(load_set([B / "synth" / "train.npz"]), dev)
    print(f"train: {len(R[0])} real cubes, {len(Sy[0])} synthetic", flush=True)
    # class weights from the mix: inverse square root of the frequency
    freq = torch.zeros(K, device=dev)
    for lab in (R[1], Sy[1]):
        ok = lab != IGNORE
        f = torch.bincount(lab[ok].long(), minlength=K)[:K].float()
        freq += f / f.sum()
    w = (freq / freq.sum()).clamp_min(1e-4) ** -0.5
    w = w / w.mean()
    print("class weights:", {c: round(float(x), 2) for c, x in zip(CLASSES, w)}, flush=True)

    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    t0 = time.time()
    budget = a.minutes * 60
    step = 0
    best = -1
    hist = []
    ns = int(a.bs * a.synth_frac)
    nr = a.bs - ns
    next_eval = 300
    while True:
        el = time.time() - t0
        if el > budget:
            break
        lr = a.lr * 0.5 * (1 + np.cos(np.pi * min(1.0, el / budget)))
        for pg in opt.param_groups:
            pg["lr"] = lr
        ir = torch.randint(0, len(R[0]), (nr,), device=dev)
        isy = torch.randint(0, len(Sy[0]), (ns,), device=dev)
        ch = torch.cat([R[0][ir], Sy[0][isy]])
        lab = torch.cat([R[1][ir], Sy[1][isy]])
        vis = torch.cat([R[2][ir], Sy[2][isy]])
        mask = torch.rand(a.bs, device=dev) < a.mask_frac
        x, y = make_input(ch, lab, vis, mask)
        x, y = augment(x, y, g)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            logits = net(x)
        loss = F.cross_entropy(logits.float(), y, weight=w, ignore_index=IGNORE)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(net.parameters(), 5.0)
        opt.step()
        step += 1
        if step % 200 == 0:
            print(f"step {step} {el / 60:.1f} min loss {loss.item():.4f} lr {lr:.2e}", flush=True)
        if el > next_eval:
            next_eval += 300
            best = checkpoint(net, sets, dev, out, step, el, loss.item(), best, hist)
    best = checkpoint(net, sets, dev, out, step, time.time() - t0, loss.item(), best, hist)
    print(f"done: {step} steps in {(time.time() - t0) / 60:.1f} min", flush=True)
    net.load_state_dict(torch.load(out / "best.pt", map_location=dev)["model"])
    res = eval_all(net, sets, dev)
    res["history"] = hist
    (out / "eval.json").write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
