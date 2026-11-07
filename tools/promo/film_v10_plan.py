"""V10's one frame grid: shots, captions and voice, written out as the JSON assemble_film.py reads, and the caption
strips drawn as PNGs. Anchors are the music's own: the hit at 8.00 s, the break at 36-39.5 s, the last hit at 40 s.

    python film_v10_plan.py <out_dir> <laptop_root>
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FPS = 30
FONT = r"C:\Windows\Fonts\segoeuib.ttf"
FONT_SMALL = r"C:\Windows\Fonts\segoeui.ttf"


def strip(path, text, size=50, small=None):
    """white words on the picture itself, no plate: a soft dark halo keeps them readable on grass and on sky"""
    w, h = 1920, 190
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, size)
    tw = d.textlength(text, font=f)
    x, y = (w - tw) / 2, 30
    halo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    hd.text((x, y), text, font=f, fill=(0, 0, 0, 255), stroke_width=7, stroke_fill=(0, 0, 0, 255))
    if small:
        fs = ImageFont.truetype(FONT_SMALL, 30)
        sw = hd.textlength(small, font=fs)
        hd.text(((w - sw) / 2, y + size + 22), small, font=fs, fill=(0, 0, 0, 255), stroke_width=5, stroke_fill=(0, 0, 0, 255))
    halo = halo.filter(ImageFilter.GaussianBlur(7))
    halo.putalpha(halo.getchannel("A").point(lambda v: min(255, int(v * 0.8))))
    img = Image.alpha_composite(img, halo)
    d = ImageDraw.Draw(img)
    d.text((x, y), text, font=f, fill=(255, 255, 255, 255), stroke_width=1, stroke_fill=(20, 20, 20, 200))
    if small:
        d.text(((w - sw) / 2, y + size + 22), small, font=fs, fill=(235, 235, 235, 255))
    img.save(path)


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
        ("exit", hook + "ext_exit.mp4", 3.1, 66, 1, None, "ticks 11502-11546: out over the furnace"),
        ("run_wide", hook + "run_side.mp4", 1.3, 48, 1, None, "ticks 12596-12628: four lanes, real MOVE orders"),
        ("run_close", hook + "run_close.mp4", 0.6, 120, 1, None, "ticks 12592-12672: all four run at the camera"),
        ("furnace", hook + "hop_close.mp4", 1.65, 87, 1, None, "tick 363-421: the furnace right in front of the door"),
        ("hop", hook + "hop_close.mp4", 4.55, 66, 1, None, "ticks 421-465: Agent 4 climbs onto the furnace to the door"),
        ("plank", S("captures", "door_agent3_20261107", "door_inside_break_attempt6.mp4"), 0.2, 96, 1.1, None, "earlier take of the same house: the one plank above the door cracks and goes (WorldRec tick 444)"),
        ("ui_chop", S("takes", "colony_v4_fixed_ui.mp4"), 6.8, 51, 1, None, "real Commander: order for wood"),
        ("ui_farm", S("takes", "colony_v4_fixed_ui.mp4"), 18.2, 54, 1, None, "real Commander: order for the farm"),
        ("chop", hook + "chop_side.mp4", 1.0, 90, 1, None, "colony_v4_fixed ticks 1740-1800: Agent 2 at the trunk"),
        ("farm", S("captures", "colony_v4_closeups", "farm_clean.mp4"), 3.4, 96, 2, "crop=1152:648:380:170", "Agent 4 tills and sows, the plot appears"),
        ("hunt", S("captures", "colony_v4_payoff3", "hero3_hunt_close_1200_1260.mp4"), 0.15, 64, 1, None, "Agent 3 hits the pig, porkchop drops"),
        ("craft", S("edit", "laptop_v4_story_pass1", "pick_recipe_zoom.mp4"), 0.0, 78, 1, None, "real craft card: iron pickaxe"),
        ("axe", S("captures", "handoff_agent2_20261107", "agent2_receives_axe_attempt10.mp4"), 0.0, 44, 1, None, "Agent 2 with the axe beside Agent 1, no flying item shown"),
        ("iron", S("captures", "mine_agent1_20261107", "mine_iron_pan_attempt8.mp4"), 0.0, 96, 1, None, "Agent 1 breaks iron ore, it drops"),
        ("iron_count", S("edit", "story_v5_20261107", "mine_iron_count_zoom_v1.mp4"), 0.0, 51, 1, None, "real inventory: ore 5 to 6"),
        ("cave", S("verification", "cave_9f7afd2", "cave_bridge_live_attempt2.mp4"), 0.0, 150, 1, None, "real CaveView: loaded passages above and below, marked PART"),
        ("routine", S("verification", "mine_live_0abc1e8_20261107", "crud_F.mp4"), 190.0, 63, 1, None, "real routine edit, Save, list"),
        ("end", hook + "end_team.mp4", 0.0, 116, 1, None, "outcome take ticks 186-260: Agent 1 climbs out to the other three"),
    ]
    at, table = 0, []
    for s in shots:
        table.append({"id": s[0], "from": at, "to": at + s[3], "t": round(at / FPS, 3), "note": s[6]})
        at += s[3]
    start = {t["id"]: t["from"] for t in table}
    total = at
    cap = [
        ("Четыре ИИ-агента в Minecraft", "inside", 0, 80, None),
        ("Ночь. Дверь закрыта, все внутри", "night", 6, 90, "настоящая запись игры, ускорено"),
        ("Утро", "dawn_door", 0, 58, None),
        ("Все четверо — на работу", "run_wide", 0, 120, None),
        ("Агент 2 рубит дерево", "chop", 4, 86, None),
        ("Агент 4 вспахивает и сеет", "farm", 4, 92, None),
        ("Агент 3 добывает мясо", "hunt", 2, 62, None),
        ("Агент 1 делает кирку", "craft", 4, 74, None),
        ("…а топор отдаёт Агенту 2", "axe", 0, 44, None),
        ("Агент 1 добывает железо", "iron", 4, 92, None),
        ("Руды стало на одну больше", "iron_count", 0, 51, None),
        ("Шахта прямо в игре: ходы выше и ниже", "cave", 8, 142, None),
        ("Сценарии можно править и удалять", "routine", 0, 63, None),
    ]
    captions = []
    for i, (text, sid, a, b, small) in enumerate(cap):
        p = os.path.join(out, "cap_%02d.png" % i)
        strip(p, text, small=small)
        captions.append({"png": root + "\\edit\\v10\\caps\\cap_%02d.png" % i, "from": start[sid] + a, "to": start[sid] + b})
    vo = root + "\\edit\\v10\\vo\\"
    voice = [
        {"file": vo + "l1a.mp3", "at": 12, "gain_db": 1.5},
        {"file": vo + "l2.mp3", "at": start["run_wide"] - 12, "gain_db": 1.5},
        {"file": vo + "l3a.mp3", "at": start["furnace"] + 6, "gain_db": 1.5},
        {"file": vo + "l4.mp3", "at": start["ui_chop"] + 8, "gain_db": 1.5},
        {"file": vo + "l5.mp3", "at": start["end"] + 2, "gain_db": 1.5},
    ]
    plan = {"fps": FPS, "size": [1920, 1080], "music": root + "\\music\\epic_b_cut.wav", "music_in": 0, "music_gain_db": -5, "tail_fade": 1.6,
            "caption_y": 836, "shots": [{"id": s[0], "source": s[1], "in": s[2], "frames": s[3], "speed": s[4], **({"vf": s[5]} if s[5] else {})} for s in shots],
            "captions": captions, "voice": voice}
    json.dump(plan, open(os.path.join(out, "plan.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(table, open(os.path.join(out, "scenes.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for t in table:
        print("%6.2f %4d %s" % (t["t"], t["to"] - t["from"], t["id"]))
    print("total", total, total / FPS)


if __name__ == "__main__":
    main()
