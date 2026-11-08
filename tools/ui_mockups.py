"""Draws the commander's mock-ups (docs/ui/*.png) from the real layout: UiLayout is compiled with UiLayoutTest and dumped
as JSON (--dump), then each box is drawn here with the icon atlas (tools/ui_icons.py) and the game's own font (taken from
lib/download/client-1.7.10.jar when it is there; a plain font otherwise). The world under the panels is a made-up top-down
landscape, the texts are examples. --audit prints how much of the screen the panel of today (Commander.panelH) covers.

python tools/ui_mockups.py [--audit]"""
import io
import json
import os
import random
import shutil
import subprocess
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build import tool  # noqa: E402
import ui_icons  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "client", "src", "hardelele", "aiguide", "client")
TEST = os.path.join(ROOT, "client", "test", "hardelele", "aiguide", "client", "UiLayoutTest.java")
OUT = os.path.join(ROOT, "build", "ui-layout")
DOCS = os.path.join(ROOT, "docs", "ui")
JAR = os.path.join(ROOT, "lib", "download", "client-1.7.10.jar")

# the screens: window pixels -> the game's automatic GUI scale -> GUI units
SCREENS = {"1600x900": (1600, 900, 3, 533, 300), "854x480": (854, 480, 2, 427, 240)}
SHOTS = [("1600x900", "idle"), ("1600x900", "one"), ("1600x900", "all-stall"), ("1600x900", "chat"), ("1600x900", "everything"),
         ("854x480", "idle"), ("854x480", "one"), ("854x480", "all-stall"), ("854x480", "everything")]


def dump():
    cls = os.path.join(ROOT, "build", "ui-layout-classes")
    shutil.rmtree(cls, ignore_errors=True)
    os.makedirs(cls)
    srcs = [os.path.join(SRC, f) for f in ("UiIcon.java", "UiLayout.java", "UiToasts.java")] + [TEST]
    rel = [] if "1.8." in subprocess.run([tool("javac"), "-version"], capture_output=True, text=True).stderr else ["--release", "8"]
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", cls] + rel + srcs)
    subprocess.check_call([tool("java"), "-cp", cls, "hardelele.aiguide.client.UiLayoutTest", "--dump", OUT])


# ---- the game's font (FontRenderer of 1.7.10: ascii.png for ASCII, the unicode pages at half size for the rest) ----

class Font:
    def __init__(self):
        self.ok = os.path.isfile(JAR)
        self.pages = {}
        if not self.ok:
            self.fallback = ImageFont.load_default()
            return
        self.z = zipfile.ZipFile(JAR)
        self.ascii = Image.open(io.BytesIO(self.z.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")
        self.sizes = self.z.read("assets/minecraft/font/glyph_sizes.bin")
        a = self.ascii.load()
        cw = self.ascii.width // 16
        self.aw = {}
        for c in range(32, 127):
            x0, y0 = (c % 16) * cw, (c // 16) * cw
            right = -1
            for x in range(cw - 1, -1, -1):
                if any(a[x0 + x, y0 + y][3] for y in range(cw)):
                    right = x
                    break
            self.aw[c] = 4 if c == 32 else right + 2

    def page(self, n):
        if n not in self.pages:
            self.pages[n] = Image.open(io.BytesIO(self.z.read("assets/minecraft/textures/font/unicode_page_%02x.png" % n))).convert("RGBA")
        return self.pages[n]

    def cw(self, ch):   # advance, GUI units (half units for the unicode glyphs)
        c = ord(ch)
        if not self.ok:
            return 6
        if 32 <= c < 127:
            return self.aw[c]
        b = self.sizes[c] if c < len(self.sizes) else 0
        if b == 0:
            return 4
        s, e = b >> 4, (b & 15) + 1
        return (e - s) / 2 + 1

    def width(self, text):
        return int(sum(self.cw(ch) for ch in text) + 0.5)

    def trim(self, text, w):
        out, x = "", 0
        for ch in text:
            x += self.cw(ch)
            if x > w:
                return out
            out += ch
        return out

    def glyph(self, ch, k):
        """the glyph as a k-pixels-a-unit white image (alpha), its x offset"""
        c = ord(ch)
        if 32 <= c < 127:
            cw = self.ascii.width // 16
            g = self.ascii.crop(((c % 16) * cw, (c // 16) * cw, (c % 16) * cw + cw, (c // 16) * cw + cw))
            return g.resize((8 * k, 8 * k), Image.NEAREST)
        b = self.sizes[c] if c < len(self.sizes) else 0
        s, e = b >> 4, (b & 15) + 1
        p = self.page(c >> 8)
        u, v = (c & 15) * 16, ((c >> 4) & 15) * 16
        g = p.crop((u + s, v, u + e, v + 16))
        return g.resize((max(1, round((e - s) * k / 2)), 8 * k), Image.NEAREST)

    def draw(self, img, x, y, text, color, k, shadow=True):
        """text at GUI x, y on an image k pixels a unit; the game's shadow: the colour /4, one unit down right"""
        if not self.ok:
            d = ImageDraw.Draw(img)
            if shadow:
                d.text((x * k + k, y * k + k), text, fill=tuple(v // 4 for v in color), font=self.fallback)
            d.text((x * k, y * k), text, fill=color, font=self.fallback)
            return
        for col, off in (([v // 4 for v in color], k) if shadow else (None, 0), (color, 0)):
            if col is None:
                continue
            cx = x * k + off
            for ch in text:
                if ch != " ":
                    g = self.glyph(ch, k)
                    tint = Image.new("RGBA", g.size, tuple(col) + (255,))
                    tint.putalpha(g.getchannel("A"))
                    img.alpha_composite(tint, (int(cx), int(y * k + off)))
                cx += self.cw(ch) * k


# ---- drawing ------------------------------------------------------------------------------------------------

def argb(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255, (v >> 24) & 255)


PANEL, PANEL_HI, BUTTON, BUTTON_ON, BUTTON_OVER, TIP = argb(0xD0101820), argb(0xF015222E), argb(0xC0283038), argb(0xF0407A50), argb(0xE0405060), argb(0xF0101418)
WARN, WARN_TEXT, TEXT, DIM, KEY = argb(0xE0503A10), (255, 224, 154), (255, 255, 255), (154, 168, 184), (255, 224, 138)


class Canvas:
    def __init__(self, size, k, gw, gh, font, atlas):
        self.k, self.gw, self.gh, self.font, self.atlas = k, gw, gh, font, atlas
        self.img = world(size, k)

    def round(self, x, y, w, h, r, col):
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).rounded_rectangle([x * self.k, y * self.k, (x + w) * self.k - 1, (y + h) * self.k - 1], radius=r * self.k, fill=col)
        self.img.alpha_composite(layer)

    def icon(self, name, x, y, size=16, alpha=1.0):
        i = [n for n, _ in ui_icons.ICONS].index(name)
        large = self.k % 2 == 0
        cx, cy, s = ui_icons.cell(i, large)
        ic = self.atlas.crop((cx, cy, cx + s, cy + s)).resize((size * self.k, size * self.k), Image.NEAREST)
        if alpha < 1:
            ic.putalpha(ic.getchannel("A").point(lambda v: int(v * alpha)))
        self.img.alpha_composite(ic, (int(x * self.k), int(y * self.k)))

    def text(self, x, y, s, col=TEXT, shadow=True):
        self.font.draw(self.img, x, y, s, col[:3], self.k, shadow)

    def button(self, b, name, on=False, over=False, off=False):
        self.round(b["x"], b["y"], b["w"], b["h"], 5, argb(0x80202830) if off else BUTTON_ON if on else BUTTON_OVER if over else BUTTON)
        self.icon(name, b["x"] + 2, b["y"] + 2, 16, 0.45 if off else 1.0)
        if on:
            self.round(b["x"] + 4, b["y"] + b["h"] - 2, b["w"] - 8, 1, 0, (185, 255, 206, 255))

    def tooltip(self, b, words, key):
        f = self.font
        kw = f.width(key) + 8 if key else 0
        tw = f.width(words) + (kw + 6 if kw else 0) + 8
        th = 16
        x = max(4, min(self.gw - 4 - tw, b["x"] + b["w"] // 2 - tw // 2))
        y = b["y"] - th - 3
        if y < 4:
            y = b["y"] + b["h"] + 3
        self.round(x, y, tw, th, 3, TIP)
        self.text(x + 4, y + 4, words)
        if kw:
            kx = x + 4 + f.width(words) + 6
            self.round(kx, y + 2, kw, 12, 2, (58, 68, 80, 255))
            self.text(kx + 4, y + 4, key, KEY)


def world(size, k):
    """a made-up landscape seen from above: grass, trees, a path, water, a house (blocks of 8 GUI units)"""
    rnd = random.Random(7)
    w, h = size
    img = Image.new("RGBA", size, (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    b = 8 * k
    for gy in range(0, h // b + 1):
        for gx in range(0, w // b + 1):
            g = rnd.randint(-10, 10)
            col = (96 + g, 156 + g, 64 + g)
            if (gx - 8) ** 2 / 30 + (gy - h // b + 3) ** 2 / 8 < 4:
                col = (54 + g // 2, 92 + g, 200)
            if abs(gy - (gx * 0.35 + 12)) < 1:
                col = (150 + g, 112 + g, 70)
            d.rectangle([gx * b, gy * b, gx * b + b - 1, gy * b + b - 1], fill=col)
    for _ in range(60):
        x, y = rnd.randrange(w), rnd.randrange(h)
        r = rnd.randint(2, 3) * b // 2
        d.rectangle([x - r, y - r, x + r, y + r], fill=(40, 96, 36))
        d.rectangle([x - r + b // 2, y - r + b // 2, x + r - b // 2, y + r - b // 2], fill=(52, 116, 44))
    hx, hy = w // 2 - 3 * b, h // 2 - 4 * b
    d.rectangle([hx, hy, hx + 6 * b, hy + 5 * b], fill=(160, 120, 70))
    d.rectangle([hx + b, hy + b, hx + 5 * b, hy + 4 * b], fill=(120, 84, 48))
    return img


def agent(c, x, y, n, picked, player=False):
    """an agent from above with the world label of the new design: a number badge and a health line (no names)"""
    k = c.k
    d = ImageDraw.Draw(c.img)
    if picked:
        for r in (7, 8):
            d.ellipse([(x - r) * k, (y - r // 2) * k + 6 * k, (x + r) * k, (y + r // 2) * k + 6 * k], outline=(90, 255, 128, 255), width=k)
    d.rectangle([(x - 3) * k, (y - 2) * k, (x + 3) * k, (y + 6) * k], fill=(60, 70, 160) if not player else (40, 120, 200))
    d.rectangle([(x - 2) * k, (y - 5) * k, (x + 2) * k, (y - 1) * k], fill=(222, 168, 128))
    c.round(x - 6, y - 17, 12, 10, 3, (47, 111, 168, 230) if player else (24, 28, 36, 200) if not picked else (32, 72, 40, 230))
    c.text(x - c.font.width(str(n)) / 2, y - 16, str(n), (144, 255, 144) if picked else TEXT)
    if n == 3:   # its mind is choosing a step: a small cloud over the number (the words are on its chip and card)
        c.icon("think", x - 4, y - 27, 8)
    c.round(x - 6, y - 6, 12, 2, 0, (32, 32, 32, 255))
    c.round(x - 6, y - 6, 10 if n != 3 else 5, 2, 0, (80, 224, 112, 255) if n != 3 else (224, 192, 64, 255))


# example contents ---------------------------------------------------------------------------------------------

DOING = {1: "self_ai", 2: "mine", 3: "think", 4: "chop", 5: "deposit", 6: "move"}
ORDER_WORDS = {"move": "Идти", "chop": "Рубить", "mine": "Копать", "attack": "Атаковать", "guard": "Охранять", "deposit": "Сложить в сундук",
               "craft": "Крафт", "smelt": "Переплавка", "place": "Поставить", "stop": "Стоп", "plan": "По плану", "goal": "Цель агенту",
               "gather": "Собраться", "dismiss": "Отпустить", "chat": "Сказать гиду", "close": "Закрыть", "scenarios": "Сценарии", "add_agent": "Ещё агент",
               "self_ai": "Отдать себя ИИ", "rate": "Оценить действия", "share": "Поделиться данными", "settings": "Настройки", "help": "Клавиши",
               "fog": "Туман", "places": "Места", "log": "Журнал", "caves": "Пещера 3D", "layer": "Слой"}
KEYS = {"move": "M", "chop": "B", "attack": "X", "guard": "G", "stop": "H", "plan": "R", "chat": "T", "add_agent": "=", "self_ai": "O",
        "dismiss": "Delete", "rate": "J", "places": "L", "fog": "Y", "caves": "C", "layer": "U", "help": "F1", "close": "Esc"}
TOASTS = [("chat", "Гид: Агент 2 пошёл за железом, остальные по плану."), ("rate", "Оценка записана"),
          ("add_agent", "Агент 5 пришёл."), ("warning", "Больше агентов нельзя: их уже 4, с тобой — 5.")]
JOURNAL = [("mine", "12:41  Агент 2: копает железную руду ×6"), ("think", "12:41  Агент 3 решил: сначала факелы"),
           ("deposit", "12:40  Агент 5 сложил в сундук: 32 дуба"), ("chat", "12:39  Гид: принял, иду за железом"),
           ("warning", "12:38  Агент 4: нет пути к цели")]


def render(screen, state, font, atlas):
    W, H, k, gw, gh = SCREENS[screen]
    boxes = json.load(open(os.path.join(OUT, "%dx%d-%s.json" % (gw, gh, state)), encoding="utf-8"))
    by = {b["id"]: b for b in boxes}
    c = Canvas((W, H), k, gw, gh, font, atlas)
    sel = {"idle": set(), "one": {2}, "all-stall": {1, 2, 3, 4, 5, 6}, "chat": {2}, "everything": {3}}[state]
    units = 6 if state in ("all-stall", "everything") else 5
    for n, (x, y) in zip(range(1, units + 1), [(0.30, 0.42), (0.45, 0.52), (0.62, 0.40), (0.70, 0.60), (0.38, 0.62), (0.55, 0.33)]):
        agent(c, int(gw * x), int(gh * y), n, n in sel, n == 1)
    for b in boxes:
        i = b["id"]
        if i in ("toggles", "bar"):
            c.round(b["x"], b["y"], b["w"], b["h"], 6, PANEL)
    for b in boxes:
        i = b["id"]
        if i.startswith("roster.") and i != "roster.more":
            n = int(i.split(".")[1])
            picked = n in sel
            c.round(b["x"], b["y"], b["w"], b["h"], 4, (42, 92, 74, 224) if picked else PANEL)
            c.round(b["x"] + 2, b["y"] + 2, 11, 11, 3, (47, 111, 168, 255) if n == 1 else (58, 68, 80, 255))
            c.text(b["x"] + 8 - font.width(str(n)) / 2, b["y"] + 4, str(n))
            if b["w"] >= 34:
                c.icon(DOING.get(n, "move"), b["x"] + 16, b["y"] + 1, 16, 1.0 if n != 3 else 0.9)
            c.round(b["x"] + 2, b["y"] + 16, b["w"] - 4, 2, 0, (32, 32, 32, 255))
            c.round(b["x"] + 2, b["y"] + 16, int((b["w"] - 4) * (0.45 if n == 3 else 1)), 2, 0, (224, 192, 64, 255) if n == 3 else (80, 224, 112, 255))
        elif i == "roster.more":
            c.round(b["x"], b["y"], b["w"], b["h"], 4, PANEL)
            c.text(b["x"] + 4, b["y"] + 6, "+2")
        elif i.startswith("toggle.") or i.startswith("bar.") or i.startswith("card.order."):
            name = i.split(".")[-1]
            c.button(b, name, on=(name in ("places",) or (name == "close")), off=(name == "places" and state == "everything"))
        elif i == "state":
            c.round(b["x"], b["y"], b["w"], b["h"], 5, WARN)
            c.icon("warning", b["x"] + 4, b["y"] + 4, 8)
            c.text(b["x"] + 15, b["y"] + 4, font.trim("План стоит: все агенты на твоих приказах", b["w"] - 38), WARN_TEXT)
            c.round(b["x"] + b["w"] - 18, b["y"] + 1, 14, 14, 3, BUTTON)
            c.icon("plan", b["x"] + b["w"] - 17, b["y"] + 4, 8)
        elif i.startswith("toast.") and i != "toast.more":
            ic, t = TOASTS[int(i.split(".")[1]) % len(TOASTS)]
            warn = ic == "warning"
            c.round(b["x"], b["y"], b["w"], b["h"], 5, WARN if warn else PANEL_HI)
            c.icon(ic, b["x"] + 4, b["y"] + 3, 8)
            c.text(b["x"] + 15, b["y"] + 3, font.trim(t, b["w"] - 19), WARN_TEXT if warn else TEXT)
        elif i == "toast.more":
            c.text(b["x"] + b["w"] // 2 - 20, b["y"], "ещё 1 · журнал", DIM)
        elif i == "journal":
            c.round(b["x"], b["y"], b["w"], b["h"], 5, PANEL_HI)
            for j in range((b["h"] - 8) // 10):
                ic, t = JOURNAL[j % len(JOURNAL)]
                c.icon(ic, b["x"] + 4, b["y"] + 4 + j * 10, 8)
                c.text(b["x"] + 15, b["y"] + 5 + j * 10, font.trim(t, b["w"] - 19), TEXT if j else (184, 224, 255))
        elif i == "chat":
            c.round(b["x"], b["y"], b["w"], b["h"], 7, argb(0xF0202C3A))
            off = state == "everything"
            c.icon("offline" if off else "chat", b["x"] + 4, b["y"] + 4, 8)
            c.text(b["x"] + 15, b["y"] + 4, "Гида нет на этом сервере: приказы кнопками работают" if off else "принеси 10 железа в сундук_", DIM if off else TEXT)
        elif i == "card":
            c.round(b["x"], b["y"], b["w"], b["h"], 7, PANEL)
    # the card's insides
    if "card.head" in by:
        hd = by["card.head"]
        one = len(sel) == 1
        n = sorted(sel)[0]
        badge = str(n) if one else str(len(sel))
        c.round(hd["x"], hd["y"], 11, 11, 3, (58, 68, 80, 255) if one else (42, 92, 74, 255))
        c.text(hd["x"] + 6 - font.width(badge) / 2, hd["y"] + 2, badge)
        name = ("Агент %d" % n) if one else "выбраны"
        c.text(hd["x"] + 15, hd["y"] + 2, name)
        hx = hd["x"] + 15 + font.width(name) + 6
        if one:
            c.icon("health", hx, hd["y"] + 1, 8)
            c.round(hx + 10, hd["y"] + 4, 32, 3, 0, (32, 32, 32, 255))
            c.round(hx + 10, hd["y"] + 4, 32 if n != 3 else 14, 3, 0, (80, 224, 112, 255) if n != 3 else (224, 192, 64, 255))
            if "card.doing" not in by:
                c.icon(DOING[n], hx + 48, hd["y"] + 1, 8)
                c.text(hx + 59, hd["y"] + 2, font.trim("решает, что дальше" if n == 3 else "копает железо", hd["x"] + hd["w"] - hx - 60), DIM)
        if one and "card.doing" in by:
            t = "думает..."
            tx = hd["x"] + hd["w"] - font.width(t) - 12
            c.icon("think", tx, hd["y"] + 1, 8)
            c.text(tx + 10, hd["y"] + 2, t, (184, 224, 255))
    if "card.doing" in by:
        b = by["card.doing"]
        n = sorted(sel)[0]
        c.icon(DOING[n], b["x"], b["y"], 8)
        c.text(b["x"] + 11, b["y"] + 1, font.trim("Сейчас: выбирает следующий шаг" if n == 3 else "Сейчас: копает железную руду ×6 · по приказу", b["w"] - 11))
    if "card.goal" in by:
        b = by["card.goal"]
        c.icon("goal", b["x"], b["y"], 8)
        c.text(b["x"] + 11, b["y"] + 1, font.trim("Цель: 10 железа в сундук (сам решает шаги)", b["w"] - 11), (184, 224, 255))
    if "card.bag" in by:
        b = by["card.bag"]
        items = ["mine", "chop", "attack", None, None, "craft", None, "smelt"]
        cols = max(1, (b["w"]) // 18)
        for r in range(b["h"] // 18):
            for j in range(cols):
                x, y = b["x"] + j * 18, b["y"] + r * 18
                held = r == 0 and j == 0
                c.round(x, y, 17, 17, 3, (96, 120, 48, 224) if held else (40, 48, 56, 144))
                it = items[(r * cols + j)] if r * cols + j < len(items) else None
                if it:
                    c.icon(it, x + 1, y + 1, 16)
                elif r * cols + j < 14:
                    c.round(x + 4, y + 4, 9, 9, 1, [(128, 128, 128, 255), (160, 116, 66, 255), (220, 220, 220, 255)][(r * cols + j) % 3])
    if "card.rows" in by:
        b = by["card.rows"]
        for j, n in enumerate(sorted(sel)[: b["h"] // 12]):
            y = b["y"] + j * 12
            c.round(b["x"], y, 9, 9, 2, (47, 111, 168, 255) if n == 1 else (58, 68, 80, 255))
            c.text(b["x"] + 5 - font.width(str(n)) / 2, y + 1, str(n))
            c.round(b["x"] + 12, y + 3, 24, 3, 0, (32, 32, 32, 255))
            c.round(b["x"] + 12, y + 3, 24 if n != 3 else 10, 3, 0, (80, 224, 112, 255) if n != 3 else (224, 192, 64, 255))
            c.icon(DOING[n], b["x"] + 40, y, 8)
            words = {1: "ты: отдан ИИ, рубит", 2: "копает железо", 3: "думает: факелы или уголь", 4: "рубит дуб", 5: "несёт в сундук", 6: "идёт к шахте"}[n]
            c.text(b["x"] + 51, y + 1, font.trim(words, b["w"] - 52))
        if len(sel) > b["h"] // 12:
            c.text(b["x"] + 51, b["y"] + b["h"] - 8, "ещё %d" % (len(sel) - b["h"] // 12), DIM)
    # one hover tooltip a shot, and a menu
    hover = {"idle": "bar.chat", "one": "card.order.chop", "all-stall": "card.order.plan", "chat": "bar.close", "everything": "card.order.stop"}[state]
    if hover in by:
        b = by[hover]
        name = hover.split(".")[-1]
        c.button(b, name, over=True)
        c.tooltip(b, ORDER_WORDS.get(name, name), KEYS.get(name, ""))
    more = [b for b in boxes if b["menu"]]
    if more and state == "everything":
        m = more[-1]
        rows = len(m["menu"])
        mw = max(font.width(ORDER_WORDS.get(n, n)) for n in m["menu"]) + 40
        x = max(4, min(gw - 4 - mw, m["x"] + m["w"] // 2 - mw // 2))
        y = m["y"] - rows * 14 - 7
        c.round(x, y, mw, rows * 14 + 4, 5, PANEL_HI)
        for j, n in enumerate(m["menu"]):
            c.icon(n, x + 4, y + 5 + j * 14, 8)
            c.text(x + 16, y + 5 + j * 14, ORDER_WORDS.get(n, n))
            if KEYS.get(n):
                c.text(x + mw - 6 - font.width(KEYS[n]), y + 5 + j * 14, KEYS[n], KEY, False)
    return c.img.convert("RGB")


def icon_sheet(font, atlas):
    """every icon at 3x on snow, grass and a night cave, with its name"""
    k, cell = 2, 30
    names = [n for n, _ in ui_icons.ICONS]
    cols = 10
    rows = (len(names) + cols - 1) // cols
    bgs = [(236, 240, 244), (98, 150, 60), (18, 20, 28)]
    img = Image.new("RGBA", (cols * cell * 3 * k + 8, rows * (cell + 10) * k + 8), (44, 48, 56, 255))
    for i, n in enumerate(names):
        x0, y0 = 4 + (i % cols) * cell * 3 * k, 4 + (i // cols) * (cell + 10) * k
        for j, bg in enumerate(bgs):
            ImageDraw.Draw(img).rectangle([x0 + j * cell * k, y0, x0 + (j + 1) * cell * k - 1, y0 + cell * k - 1], fill=bg)
            cx, cy, s = ui_icons.cell(i, False)
            ic = atlas.crop((cx, cy, cx + s, cy + s)).resize((24 * k, 24 * k), Image.NEAREST)
            img.alpha_composite(ic, (x0 + j * cell * k + 3 * k, y0 + 3 * k))
        font.draw(img, x0 / k + 2, y0 / k + cell + 1, n, (255, 255, 255), k)
    return img.convert("RGB")


def audit(font):
    """today's panel (Commander.Screen.panelH, viewBar): the share of the screen it covers, by state"""
    print("today's commander panel (Commander.panelH + viewBar), GUI units:")
    for screen in SCREENS:
        _, _, _, gw, gh = SCREENS[screen]
        band = min(gw, 420)
        for name, sel, full, me in (("nobody picked", 0, False, False), ("one agent", 1, False, False), ("one, whole bag", 1, True, False),
                                    ("three agents", 3, False, False), ("five agents", 5, False, False), ("player + one", 1, False, True)):
            h = 8 + 16 + 22 + 6 + 18 + (18 if me else 0)   # chat, a reply, the pills
            h += (14 + (80 if full else 40) + 18) if sel == 1 else (sel * 20 + 18) if sel > 1 else 12
            top = gh - h - 4
            world = top - 46
            area = (band - 8) * (h) + (band - 8) * (24 - 5) + (band - 8) * (46 - 28)
            print("  %-8s %-15s panel %3d high, top at %3d; world between the bars: %4d (%3d%%), panels %3d%% of the screen"
                  % (screen, name, h, top, world, world * 100 // gh, area * 100 // (gw * gh)))
    pills = ["Сценарии", "+ агент", "Отдать себя ИИ", "Места", "Что сделали", "Поделиться"]
    x = 12
    fit = []
    for p in pills:
        if p in ("Места", "Что сделали", "Поделиться") and x + font.width(p) + 12 > 420 - 8:
            continue
        x += font.width(p) + 12 + 4
        fit.append(p)
    print("  the pill row in a 420-wide band:", " | ".join(fit), "- width", x, "(dropped:", [p for p in pills if p not in fit], ")")


def main():
    font = Font()
    if "--audit" in sys.argv:
        audit(font)
        return
    dump()
    atlas = Image.open(ui_icons.OUT).convert("RGBA")
    os.makedirs(DOCS, exist_ok=True)
    for screen, state in SHOTS:
        out = os.path.join(DOCS, "commander-%s-%s.png" % (screen, state))
        render(screen, state, font, atlas).save(out)
        print("drawn", os.path.relpath(out, ROOT))
    icon_sheet(font, atlas).save(os.path.join(DOCS, "icons-sheet.png"))
    print("drawn docs/ui/icons-sheet.png", "" if font.ok else "(no game font: lib/download/client-1.7.10.jar is not there)")


if __name__ == "__main__":
    main()
