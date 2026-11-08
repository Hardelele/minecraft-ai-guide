"""Draws the commander's icons into one atlas: client/res/assets/aiguideclient/textures/gui/icons.png (256x256).

Each icon is drawn twice from the same strokes: at 16x16 (the top band, 16 a row, y 0..63) and at 32x32 (below,
8 a row, from y 64), every icon with a dark outline so it reads over snow and over a night cave alike. The order of
ICONS is the atlas order and UiIcon.java's order (tools/tests/test_ui_icons.py checks both). Run it again after a
change: python tools/ui_icons.py [--preview out.png]"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "client", "res", "assets", "aiguideclient", "textures", "gui", "icons.png")
ATLAS = 256
SMALL, LARGE = 16, 32
LARGE_Y = 64            # where the 32x32 band starts
OUTLINE = (22, 18, 26, 255)

# a block game's colours: wood, stone, iron, gold and the rest
WOOD, WOOD_D = (190, 140, 84), (138, 98, 56)
STONE, STONE_D, STONE_L = (128, 128, 128), (88, 88, 88), (170, 170, 170)
IRON, IRON_D = (226, 226, 226), (160, 160, 168)
GOLD, GOLD_D = (252, 214, 70), (200, 150, 30)
RED, RED_D = (214, 56, 44), (150, 30, 26)
GREEN, GREEN_D = (98, 200, 72), (54, 132, 40)
BLUE, BLUE_D = (72, 140, 232), (40, 86, 170)
CYAN = (84, 222, 230)
WHITE, PAPER, PAPER_D = (244, 244, 244), (232, 222, 190), (176, 160, 120)
PURPLE = (204, 146, 255)
ORANGE, YELLOW = (240, 140, 36), (255, 230, 90)
SKIN, HAIR, HAIR2 = (222, 168, 128), (92, 60, 32), (40, 40, 48)
DARK, SLOT = (40, 40, 44), (62, 62, 68)
GRASS, DIRT, DIRT_D = (110, 190, 70), (150, 104, 66), (110, 74, 46)


class Pen:
    """strokes on a 16-cell grid, drawn at k pixels a cell"""

    def __init__(self, img, ox, oy, k):
        self.d, self.ox, self.oy, self.k = ImageDraw.Draw(img), ox, oy, k

    def p(self, x, y):   # a cell's corner
        return self.ox + x * self.k, self.oy + y * self.k

    def c(self, x, y):   # a cell's middle
        return self.ox + x * self.k + self.k // 2, self.oy + y * self.k + self.k // 2

    def r(self, x0, y0, x1, y1, col):   # cells x0..x1, y0..y1 (inclusive)
        a, b = self.p(x0, y0)
        self.d.rectangle([a, b, a + (x1 - x0 + 1) * self.k - 1, b + (y1 - y0 + 1) * self.k - 1], fill=col + (255,))

    def ln(self, pts, col, w=1):   # through cells' middles
        self.d.line([self.c(x, y) for x, y in pts], fill=col + (255,), width=w * self.k)

    def poly(self, pts, col):     # corners
        self.d.polygon([self.p(x, y) for x, y in pts], fill=col + (255,))

    def el(self, x0, y0, x1, y1, col):
        a, b = self.p(x0, y0)
        self.d.ellipse([a, b, a + (x1 - x0 + 1) * self.k - 1, b + (y1 - y0 + 1) * self.k - 1], fill=col + (255,))


def head(p, hair, eyes=(70, 90, 200)):
    p.r(2, 3, 10, 11, SKIN)
    p.r(2, 3, 10, 5, hair)
    p.r(2, 6, 2, 7, hair)
    p.r(10, 6, 10, 7, hair)
    p.r(4, 7, 4, 8, eyes)
    p.r(8, 7, 8, 8, eyes)
    p.r(5, 10, 7, 10, (170, 110, 80))


def i_move(p):
    p.r(2, 6, 8, 9, GREEN)
    p.poly([(8, 2), (14.5, 8), (8, 14)], GREEN)
    p.r(2, 6, 8, 6, (150, 240, 120))


def i_chop(p):
    p.ln([(3, 13), (10, 6)], WOOD)
    p.ln([(4, 13), (11, 6)], WOOD_D)
    p.poly([(8, 2), (12, 2), (14, 4), (14, 8), (12, 8), (9, 5)], IRON)
    p.poly([(12, 2), (14, 4), (14, 8), (13, 8), (13, 4)], IRON_D)


def i_mine(p):
    p.ln([(3, 13), (10, 6)], WOOD)
    p.ln([(4, 13), (11, 6)], WOOD_D)
    p.ln([(4, 4), (7, 2), (10, 2), (12, 4), (13, 7), (13, 11)], IRON)
    p.ln([(5, 5), (8, 3), (10, 3), (12, 5), (12, 7), (12, 10)], IRON_D)


def i_attack(p):
    p.ln([(5, 10), (13, 2)], IRON)
    p.ln([(6, 10), (13, 3)], IRON_D)
    p.ln([(3, 8), (7, 12)], GOLD_D)
    p.ln([(2, 13), (4, 11)], WOOD)
    p.r(13, 2, 13, 2, WHITE)


def i_guard(p):
    p.poly([(2, 2), (14, 2), (14, 8), (8, 14.5), (2, 8)], BLUE)
    p.poly([(8, 2), (14, 2), (14, 8), (8, 14.5)], BLUE_D)
    p.r(7, 3, 8, 12, IRON)
    p.r(3, 6, 12, 7, IRON)


def i_deposit(p):
    p.r(2, 7, 13, 14, WOOD)
    p.r(2, 9, 13, 9, WOOD_D)
    p.r(2, 7, 13, 7, (190, 140, 84))
    p.r(7, 9, 8, 11, IRON)
    p.r(7, 1, 8, 3, GOLD)
    p.poly([(5, 4), (11, 4), (8, 7)], GOLD)


def i_craft(p):
    p.r(2, 2, 13, 13, WOOD)
    for v in (5, 9):
        p.r(v + 1, 2, v + 1, 13, WOOD_D)
        p.r(2, v + 1, 13, v + 1, WOOD_D)
    p.r(2, 2, 13, 2, (196, 150, 96))
    p.r(3, 3, 5, 5, IRON)
    p.r(11, 11, 13, 13, STONE_L)


def i_smelt(p):
    p.r(2, 2, 13, 13, STONE)
    p.r(2, 2, 13, 3, STONE_L)
    p.r(5, 5, 10, 5, STONE_D)
    p.r(5, 8, 10, 12, DARK)
    p.r(6, 10, 9, 12, ORANGE)
    p.r(7, 9, 8, 11, YELLOW)


def i_place(p):
    p.poly([(1, 5), (7, 2), (13, 5), (7, 8)], GRASS)
    p.poly([(1, 5), (7, 8), (7, 14), (1, 11.5)], DIRT)
    p.poly([(7, 8), (13, 5), (13, 11.5), (7, 14)], DIRT_D)
    p.r(12, 1, 12, 5, WHITE)
    p.r(10, 3, 14, 3, WHITE)


def i_stop(p):
    p.poly([(5, 1), (10, 1), (14, 5), (14, 10), (10, 14), (5, 14), (1, 10), (1, 5)], RED)
    p.poly([(10, 1), (14, 5), (14, 10), (10, 14), (8, 14), (8, 1)], RED_D)
    p.r(4, 7, 10, 8, WHITE)


def i_plan(p):
    p.r(3, 2, 12, 14, PAPER)
    p.r(3, 2, 3, 14, PAPER_D)
    p.r(6, 1, 9, 3, STONE_D)
    for y in (6, 9, 12):
        p.ln([(4, y - 1), (5, y), (6, y - 2)], GREEN_D)
        p.r(8, y - 1, 11, y - 1, STONE)


def i_hold(p):
    p.r(1, 1, 14, 14, GOLD)
    p.r(2, 2, 13, 13, SLOT)
    p.poly([(4, 10), (7, 6), (12, 6), (10, 10)], IRON)
    p.poly([(4, 10), (10, 10), (10, 11), (4, 11)], IRON_D)


def i_add_agent(p):
    head(p, HAIR)
    p.r(10, 11, 14, 11, GREEN)
    p.r(12, 9, 12, 13, GREEN)


def i_dismiss(p):
    head(p, HAIR)
    p.r(10, 11, 14, 11, RED)


def i_self_ai(p):
    head(p, HAIR2, CYAN)
    p.r(12, 1, 12, 5, CYAN)
    p.r(10, 3, 14, 3, CYAN)


def i_goal(p):
    p.r(3, 1, 3, 14, WOOD)
    p.poly([(4, 1), (13, 1), (11, 4), (13, 7), (4, 7)], RED)
    p.r(4, 1, 12, 1, (240, 100, 90))
    p.r(2, 14, 5, 14, STONE_D)


def i_think(p):
    p.el(1, 2, 14, 11, WHITE)
    p.r(3, 11, 4, 12, WHITE)
    p.r(2, 13, 2, 13, WHITE)
    for x in (4, 7, 10):
        p.r(x, 6, x + 1, 7, BLUE_D)


def i_places(p):
    a, b, c, d = (2, 5), (9, 5), (9, 13), (2, 13)
    s = (4, -3)
    for q in ([a, b, c, d, a], [(a[0] + s[0], a[1] + s[1]), (b[0] + s[0], b[1] + s[1]), (c[0] + s[0], c[1] + s[1])],
              [a, (a[0] + s[0], a[1] + s[1])], [b, (b[0] + s[0], b[1] + s[1])], [c, (c[0] + s[0], c[1] + s[1])]):
        p.ln(q, PURPLE)


def i_rate(p):
    pts = [(8, 1), (10, 6), (14, 6), (11, 9.5), (12, 14), (8, 11.5), (4, 14), (5, 9.5), (1, 6), (6, 6)]
    p.poly(pts, GOLD)
    p.poly([(8, 1), (8, 11.5), (4, 14), (5, 9.5), (1, 6), (6, 6)], YELLOW)


def i_share(p):
    p.r(2, 10, 3, 14, STONE_L)
    p.r(12, 10, 13, 14, STONE_L)
    p.r(2, 13, 13, 14, STONE_L)
    p.r(7, 4, 8, 11, GREEN)
    p.poly([(3.5, 6), (8, 1), (12.5, 6)], GREEN)


def i_fog(p):
    p.el(1, 5, 8, 12, IRON_D)
    p.el(5, 2, 13, 10, IRON)
    p.el(9, 6, 14, 12, IRON)
    p.r(3, 9, 12, 12, IRON)
    p.r(2, 14, 13, 14, IRON_D)


def i_caves(p):
    p.r(1, 2, 14, 13, STONE)
    p.r(1, 2, 14, 3, STONE_L)
    p.poly([(4, 14), (4, 9), (6, 6), (10, 6), (12, 9), (12, 14)], DARK)
    p.r(7, 6, 7, 8, STONE_D)
    p.r(10, 12, 10, 13, (90, 120, 230))


def i_layer(p):
    p.r(2, 2, 13, 4, STONE)
    p.r(2, 7, 13, 9, CYAN)
    p.r(2, 12, 13, 14, STONE)
    p.r(2, 2, 13, 2, STONE_L)
    p.r(2, 12, 13, 12, STONE_L)


def i_scenarios(p):
    p.r(3, 1, 12, 14, (140, 50, 40))
    p.r(4, 12, 12, 13, PAPER)
    p.r(3, 1, 4, 14, (100, 34, 28))
    p.r(9, 1, 10, 6, GOLD)
    p.r(6, 4, 7, 5, GOLD_D)


def i_chat(p):
    p.r(1, 2, 14, 10, (150, 210, 250))
    p.r(2, 1, 13, 11, (150, 210, 250))
    p.r(3, 12, 4, 12, (150, 210, 250))
    p.r(2, 13, 2, 13, (150, 210, 250))
    p.r(4, 4, 11, 4, BLUE_D)
    p.r(4, 7, 9, 7, BLUE_D)


def i_settings(p):
    for x0, y0, x1, y1 in ((7, 1, 8, 14), (1, 7, 14, 8)):
        p.r(x0, y0, x1, y1, IRON_D)
    p.ln([(3, 3), (12, 12)], IRON_D, 2)
    p.ln([(3, 12), (12, 3)], IRON_D, 2)
    p.el(3, 3, 12, 12, IRON)
    p.el(6, 6, 9, 9, DARK)


def i_more(p):
    for x in (2, 7, 12):
        p.r(x, 7, x + 1, 8, WHITE)


def i_gather(p):
    p.r(7, 7, 8, 8, GOLD)
    for q in (((2, 4), (5, 7)), ((2, 11), (5, 8)), ((13, 4), (10, 7)), ((13, 11), (10, 8)),
              ((4, 2), (7, 5)), ((11, 2), (8, 5)), ((4, 13), (7, 10)), ((11, 13), (8, 10))):
        p.ln(list(q), GREEN)


def i_inventory(p):
    p.r(1, 1, 14, 14, STONE_D)
    for i in range(3):
        for j in range(3):
            p.r(2 + i * 4, 2 + j * 4, 4 + i * 4, 4 + j * 4, STONE_L)
    p.r(6, 6, 8, 8, GOLD)


def i_warning(p):
    p.poly([(8, 1), (14.5, 14), (1.5, 14)], YELLOW)
    p.r(7, 5, 8, 10, DARK)
    p.r(7, 12, 8, 13, DARK)


def i_offline(p):
    p.r(1, 2, 14, 10, STONE_L)
    p.r(2, 1, 13, 11, STONE_L)
    p.r(3, 12, 4, 12, STONE_L)
    p.ln([(2, 13), (13, 2)], RED, 2)


def i_log(p):
    p.r(3, 1, 12, 14, PAPER)
    p.r(3, 1, 12, 2, PAPER_D)
    for y in (4, 7, 10):
        p.r(5, y, 10, y, STONE_D)
    p.r(5, 12, 8, 12, STONE_D)


def i_help(p):
    p.el(1, 1, 14, 14, BLUE)
    p.r(5, 4, 10, 4, WHITE)
    p.r(10, 4, 10, 7, WHITE)
    p.r(7, 7, 10, 7, WHITE)
    p.r(7, 7, 7, 9, WHITE)
    p.r(7, 11, 7, 12, WHITE)
    p.r(5, 4, 5, 5, WHITE)


def i_close(p):
    p.ln([(3, 3), (12, 12)], WHITE, 2)
    p.ln([(3, 12), (12, 3)], WHITE, 2)


def i_health(p):
    p.el(1, 2, 8, 9, RED)
    p.el(7, 2, 14, 9, RED)
    p.poly([(1.5, 7), (14.5, 7), (8, 14)], RED)
    p.r(3, 4, 4, 5, (250, 160, 150))


def mouse(p, left):
    p.r(4, 1, 11, 14, IRON)
    p.r(4, 1, 11, 1, IRON_D)
    p.r(4, 7, 11, 7, IRON_D)
    p.r(7, 1, 8, 7, IRON_D)
    if left:
        p.r(4, 2, 6, 6, GOLD)
    else:
        p.r(9, 2, 11, 6, GOLD)


def i_waves(p):
    zombie, zombie_d = (96, 168, 82), (58, 112, 50)
    p.r(2, 2, 13, 13, zombie)
    p.r(2, 2, 13, 4, zombie_d)
    p.r(2, 5, 2, 7, zombie_d)
    p.r(13, 5, 13, 7, zombie_d)
    p.r(4, 7, 6, 8, DARK)
    p.r(9, 7, 11, 8, DARK)
    p.r(7, 9, 8, 10, zombie_d)
    p.r(5, 11, 10, 12, RED_D)


ICONS = [("move", i_move), ("chop", i_chop), ("mine", i_mine), ("attack", i_attack), ("guard", i_guard), ("deposit", i_deposit),
         ("craft", i_craft), ("smelt", i_smelt), ("place", i_place), ("stop", i_stop), ("plan", i_plan), ("hold", i_hold),
         ("add_agent", i_add_agent), ("dismiss", i_dismiss), ("self_ai", i_self_ai), ("goal", i_goal), ("think", i_think),
         ("places", i_places), ("rate", i_rate), ("share", i_share), ("fog", i_fog), ("caves", i_caves), ("layer", i_layer),
         ("scenarios", i_scenarios), ("chat", i_chat), ("settings", i_settings), ("more", i_more), ("gather", i_gather),
         ("inventory", i_inventory), ("warning", i_warning), ("offline", i_offline), ("log", i_log), ("help", i_help),
         ("close", i_close), ("health", i_health), ("lmb", lambda p: mouse(p, True)), ("rmb", lambda p: mouse(p, False)),
         ("waves", i_waves)]


def outline(icon, width):
    """a dark rim around every shape: the transparent pixels within `width` of a drawn one"""
    src = icon.load()
    w, h = icon.size
    out = icon.copy()
    o = out.load()
    for y in range(h):
        for x in range(w):
            if src[x, y][3]:
                continue
            near = any(0 <= x + dx < w and 0 <= y + dy < h and src[x + dx, y + dy][3]
                       for dx in range(-width, width + 1) for dy in range(-width, width + 1))
            if near:
                o[x, y] = OUTLINE
    return out


def cell(index, large):
    if large:
        return (index % 8) * LARGE, LARGE_Y + (index // 8) * LARGE, LARGE
    return (index % 16) * SMALL, (index // 16) * SMALL, SMALL


def draw(name, fn, k):
    icon = Image.new("RGBA", (16 * k, 16 * k), (0, 0, 0, 0))
    fn(Pen(icon, 0, 0, k))
    return outline(icon, k)


def atlas():
    if len(ICONS) > 8 * (ATLAS - LARGE_Y) // LARGE:
        raise SystemExit("too many icons for the atlas")
    img = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
    for i, (name, fn) in enumerate(ICONS):
        for large in (False, True):
            x, y, s = cell(i, large)
            img.paste(draw(name, fn, s // 16), (x, y))
    return img


def preview(img, path):
    """every icon at 3x over a snow-white, a grass and a night background (to check the outline reads on each)"""
    bgs = [(236, 240, 244), (98, 150, 60), (18, 20, 28)]
    n = len(ICONS)
    sheet = Image.new("RGBA", (n * 52 + 8, len(bgs) * 56 + 8), (60, 60, 60, 255))
    for r, bg in enumerate(bgs):
        for i in range(n):
            x, y, s = cell(i, False)
            tile = Image.new("RGBA", (52, 52), bg + (255,))
            ic = img.crop((x, y, x + s, y + s)).resize((48, 48), Image.NEAREST)
            tile.alpha_composite(ic, (2, 2))
            sheet.paste(tile, (4 + i * 52, 4 + r * 56))
    sheet.save(path)


def main():
    img = atlas()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT)
    print("icons:", len(ICONS), "->", os.path.relpath(OUT, ROOT))
    if "--preview" in sys.argv:
        preview(img, sys.argv[sys.argv.index("--preview") + 1])


if __name__ == "__main__":
    main()
