"""V10's one frame grid: shots, captions and voice, written out as the JSON assemble_film.py reads, and the caption
strips drawn as PNGs. Anchors are the music's own: the hit at 8.00 s, the break at 36-39.5 s, the last hit at 40 s.

    python film_v10_plan.py <out_dir> <laptop_root>
"""
import json, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FPS = 30
W, H, FG_Y, CAP_Y = 1080, 1920, 548, 1206
FONT_CYR = os.environ.get("FILM_FONT_CYR", "intertight-cyr.ttf")
FONT_LAT = os.environ.get("FILM_FONT_LAT", "intertight-lat.ttf")


def font(path, size, weight):
    f = ImageFont.truetype(path, size)
    try:
        f.set_variation_by_axes([weight])
    except OSError:
        pass
    return f


def runs(text):
    """Inter Tight comes as two files: a Cyrillic and a Latin one"""
    out = []
    for ch in text:
        lat = not (0x400 <= ord(ch) < 0x530)   # the Cyrillic file holds the letters only: digits and punctuation are in the Latin one
        if out and (out[-1][0] == lat or ch == " "):
            out[-1][1] += ch
        else:
            out.append([lat, ch])
    return out


def width(d, text, cyr, lat):
    return sum(d.textlength(t, font=lat if l else cyr) for l, t in runs(text))


def put(d, x, y, text, cyr, lat, **kw):
    for l, t in runs(text):
        f = lat if l else cyr
        d.text((x, y), t, font=f, **kw)
        x += d.textlength(t, font=f)


def wrap(d, text, cyr, lat, limit):
    lines, cur = [], ""
    for w in text.split(" "):
        t = (cur + " " + w).strip()
        if width(d, t, cyr, lat) > limit and cur:
            lines.append(cur); cur = w
        else:
            cur = t
    return lines + [cur]


def strip(path, text, size=62, small=None):
    """white words under the picture: a soft dark halo keeps them readable on the blurred field"""
    h = 330
    img = Image.new("RGBA", (W, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cyr, lat = font(FONT_CYR, size, 700), font(FONT_LAT, size, 700)
    lines = wrap(d, text, cyr, lat, 960)
    halo = Image.new("RGBA", (W, h), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    place = []
    y = 16
    for l in lines:
        place.append(((W - width(d, l, cyr, lat)) / 2, y, l)); y += int(size * 1.24)
    for x, yy, l in place:
        put(hd, x, yy, l, cyr, lat, fill=(0, 0, 0, 255), stroke_width=6, stroke_fill=(0, 0, 0, 255))
    fs = font(FONT_CYR, 38, 500)
    if small:
        sw = d.textlength(small, font=fs)
        hd.text(((W - sw) / 2, y + 12), small, font=fs, fill=(0, 0, 0, 255), stroke_width=4, stroke_fill=(0, 0, 0, 255))
    halo = halo.filter(ImageFilter.GaussianBlur(6))
    img = Image.alpha_composite(img, halo)
    d = ImageDraw.Draw(img)
    for x, yy, l in place:
        put(d, x, yy, l, cyr, lat, fill=(255, 255, 255, 255))
    if small:
        d.text(((W - sw) / 2, y + 12), small, font=fs, fill=(232, 232, 232, 255))
    img.save(path)


def card(path):
    """the first episode's end card: AI white, Guide turquoise, the line under it"""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    big, line, note = font(FONT_LAT, 170, 800), font(FONT_CYR, 62, 600), font(FONT_CYR, 36, 500)
    a, b = "AI ", "Guide"
    wa, wb = d.textlength(a, font=big), d.textlength(b, font=big)
    x, y = (W - wa - wb) / 2, 760
    d.text((x, y), a, font=big, fill=(255, 255, 255, 255))
    d.text((x + wa, y), b, font=big, fill=(94, 226, 208, 255))
    t = "Мод в разработке"
    d.text(((W - d.textlength(t, font=line)) / 2, y + 226), t, font=line, fill=(255, 255, 255, 255))
    t = "ИИ-гид и агенты для Minecraft 1.7.10"
    cyr, lat = note, font(FONT_LAT, 36, 500)
    put(d, (W - width(d, t, cyr, lat)) / 2, y + 322, t, cyr, lat, fill=(214, 214, 214, 255))
    img.save(path)


def track(out, captions, cardpng, card_from, total):
    """everything over the picture as one transparent video: each item fades in and out over 6 frames, on the film's grid"""
    edge = 6
    ff = subprocess.Popen(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "%dx%d" % (W, H), "-r", str(FPS), "-i", "-",
                           "-c:v", "qtrle", os.path.join(out, "captions.mov")], stdin=subprocess.PIPE)
    blank = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    full = []
    for c in captions:
        f = blank.copy(); f.paste(Image.open(c["png"]).convert("RGBA"), (0, CAP_Y)); full.append(f)
    items = [(c["from"], c["to"], f) for c, f in zip(captions, full)] + [(card_from + 6, total + edge, Image.open(cardpng).convert("RGBA"))]
    empty, cache = blank.tobytes(), {}
    for n in range(total):
        data = empty
        for i, (a, b, f) in enumerate(items):
            if a <= n < b:
                k = min(1.0, 1.0 if a == 0 else (n - a + 1) / edge, (b - n) / edge)
                key = (i, round(k, 3))
                if key not in cache:
                    if k >= 1:
                        cache[key] = f.tobytes()
                    else:
                        r, g, bl, al = f.split()
                        cache[key] = Image.merge("RGBA", (r, g, bl, al.point(lambda v: int(v * k)))).tobytes()
                data = cache[key]
        ff.stdin.write(data)
    ff.stdin.close()
    if ff.wait():
        sys.exit("caption track failed")


def main():
    out, root = sys.argv[1], sys.argv[2].rstrip("\\/")
    hook = root + "\\captures\\v10_hook\\"
    os.makedirs(out, exist_ok=True)
    S = lambda *a: root + "\\" + "\\".join(a)
    # id, source, in (s), frames, speed, optional filter, what it shows (the evidence, for the scene table)
    shots = [
        ("inside", hook + "int_close2.mp4", 0.35, 80, 1, None, "WorldRec a2 tick 478-531: all four inside, Agent 4 closes the original door (tick 509)"),
        ("night", hook + "ext_night.mp4", 0.0, 96, 2.8, None, "same recording, ticks 580-11360 played fast: the door stays shut through the night"),
        ("dawn_door", hook + "int_open2.mp4", 1.05, 64, 1, None, "ticks 11461-11504: the door opens from inside (tick 11477)"),
        ("exit", hook + "ext_exit.mp4", 3.1, 102, 1, None, "ticks 11502-11546: out over the furnace"),
        ("run_close", hook + "run_close.mp4", 0.0, 126, 1, None, "ticks 12580-12672: all four run at the camera"),
        ("furnace", hook + "hop_close.mp4", 1.65, 87, 1, None, "tick 363-421: the furnace right in front of the door"),
        ("hop", hook + "hop_close.mp4", 4.55, 66, 1, None, "ticks 421-465: Agent 4 climbs onto the furnace to the door"),
        ("plank", S("captures", "door_agent3_20261107", "door_inside_break_attempt6.mp4"), 0.2, 96, 1.1, None, "earlier take of the same house: the one plank above the door cracks and goes (WorldRec tick 444)"),
        ("ui_chop", S("takes", "colony_v4_fixed_ui.mp4"), 6.8, 54, 1, "crop=1440:810:240:270", "real Commander: order for wood"),
        ("ui_farm", S("takes", "colony_v4_fixed_ui.mp4"), 18.2, 57, 1, "crop=1440:810:240:270", "real Commander: order for the farm"),
        ("chop", hook + "chop_side.mp4", 1.0, 90, 1, None, "colony_v4_fixed ticks 1740-1800: Agent 2 at the trunk"),
        ("farm", S("captures", "colony_v4_closeups", "farm_clean.mp4"), 3.4, 96, 2, "crop=1152:648:380:170", "Agent 4 tills and sows, the plot appears"),
        ("hunt", S("captures", "colony_v4_payoff3", "hero3_hunt_close_1200_1260.mp4"), 0.15, 64, 1, None, "Agent 3 hits the pig, porkchop drops"),
        ("craft", S("edit", "laptop_v4_story_pass1", "pick_recipe_zoom.mp4"), 0.0, 78, 1, None, "real craft card: iron pickaxe"),
        ("axe", S("captures", "handoff_agent2_20261107", "agent2_receives_axe_attempt10.mp4"), 0.0, 44, 1, None, "Agent 2 with the axe beside Agent 1, no flying item shown"),
        ("iron", S("captures", "mine_agent1_20261107", "mine_iron_pan_attempt8.mp4"), 0.0, 96, 1, "eq=gamma=1.6:contrast=1.1:saturation=1.3", "Agent 1 breaks iron ore, it drops"),
        ("iron_count", S("edit", "story_v5_20261107", "mine_iron_count_zoom_v1.mp4"), 0.0, 51, 1, None, "real inventory: ore 5 to 6"),
        ("cave", S("verification", "cave_9f7afd2", "cave_bridge_live_attempt2.mp4"), 0.0, 150, 1, None, "real CaveView: loaded passages above and below, marked PART"),
        ("routine", S("verification", "mine_live_0abc1e8_20261107", "crud_F.mp4"), 190.0, 78, 1, None, "real routine edit, Save, list"),
        ("end", hook + "end_team.mp4", 0.0, 116, 1, None, "outcome take ticks 186-260: Agent 1 climbs out to the other three"),
        ("card", hook + "run_front.mp4", 11.0, 96, 1, "boxblur=24:2,eq=brightness=-0.16:saturation=0.8", "end card over the crew, blurred: AI Guide, in development"),
    ]
    at, table = 0, []
    for s in shots:
        table.append({"id": s[0], "from": at, "to": at + s[3], "t": round(at / FPS, 3), "note": s[6]})
        at += s[3]
    start = {t["id"]: t["from"] for t in table}
    total = at
    cap = [
        ("Смогут ли четыре ИИ-агента пройти Minecraft?", "inside", 0, 114, None),
        ("Ночь. Дверь закрыта, все внутри", "night", 36, 92, "настоящая запись игры, ускорено"),
        ("Утро", "dawn_door", 0, 58, None),
        ("За ними интересно наблюдать", "exit", 47, 102, None),
        ("Они тупят, делают задачи криво, но это забавно", "run_close", 0, 124, None),
        ("Печка по плану встала прямо перед дверью", "furnace", 6, 80, None),
        ("Поэтому домой они лезут поверх неё", "furnace", 80, 153, None),
        ("…а над дверью пришлось сломать доску", "plank", 0, 92, None),
        ("Утром я раздаю задания, а дальше они сами", "ui_chop", 6, 110, None),
        ("Агент 2 рубит дерево", "chop", 4, 86, None),
        ("Агент 4 вспахивает и сеет", "farm", 4, 92, None),
        ("Агент 3 добывает мясо", "hunt", 2, 62, None),
        ("Агент 1 делает кирку и топор себе", "craft", 4, 76, None),
        ("…а следующий топор — Агенту 2", "axe", 0, 44, None),
        ("Агент 1 добывает железо", "iron", 4, 92, None),
        ("Руды стало на одну больше", "iron_count", 0, 51, None),
        ("Шахта прямо в игре: ходы выше и ниже", "cave", 8, 142, None),
        ("Сценарии можно править и удалять", "routine", 0, 56, None),
        ("Железо есть, ферма засеяна", "routine", 58, 134, None),
        ("До конца игры ещё далеко", "routine", 134, 192, None),
    ]
    captions = []
    for i, (text, sid, a, b, small) in enumerate(cap):
        p = os.path.join(out, "cap_%02d.png" % i)
        strip(p, text, small=small)
        captions.append({"png": p, "from": start[sid] + a, "to": start[sid] + b})
    cardpng = os.path.join(out, "card.png")
    card(cardpng)
    track(out, captions, cardpng, start["card"], total)
    vo = root + "\\edit\\v10\\vo4\\"
    voice = [
        {"file": vo + "l1.mp3", "at": 3, "gain_db": 2},
        {"file": vo + "l2.mp3", "at": start["exit"] + 47, "gain_db": 2},
        {"file": vo + "l3.mp3", "at": start["furnace"] + 6, "gain_db": 2},
        {"file": vo + "l4.mp3", "at": start["ui_chop"] + 6, "gain_db": 2},
        {"file": vo + "l5.mp3", "at": start["routine"] + 56, "gain_db": 2},
    ]
    plan = {"fps": FPS, "size": [1920, 1080], "music": root + "\\music\\epic_b_cut.wav", "music_in": 0, "music_gain_db": -5, "tail_fade": 2.2, "duck": 0.24,
            "vertical": {"w": W, "h": H, "fg_y": FG_Y, "card_from": start["card"]}, "shots": [{"id": s[0], "source": s[1], "in": s[2], "frames": s[3], "speed": s[4], **({"vf": s[5]} if s[5] else {})} for s in shots],
            "caption_track": root + "\\edit\\v10\\captions.mov", "voice": voice}
    json.dump(plan, open(os.path.join(out, "plan.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(table, open(os.path.join(out, "scenes.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for t in table:
        print("%6.2f %4d %s" % (t["t"], t["to"] - t["from"], t["id"]))
    print("total", total, total / FPS)


if __name__ == "__main__":
    main()
