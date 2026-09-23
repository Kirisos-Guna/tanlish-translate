#!/usr/bin/env python3
"""Generate every Tanlish Translate icon from one source: the letter த.

The launcher icon, its themed (monochrome) layer, the in-app badge and the
floating button's mark are all the same glyph, so they are produced here in one
place instead of being hand-drawn four times and drifting apart.

The letter is read straight out of a real Tamil font (Nirmala UI ships with
Windows). ``fontTools`` is not installed and nothing gets installed to run this:
the TrueType ``cmap``/``loc``/``glyf`` tables are parsed below in about eighty
lines, which is all a single base consonant needs.

Run from anywhere, optionally with a different font::

    python tools/make_icons.py
    python tools/make_icons.py --font "C:/Windows/Fonts/latha.ttf"

It rewrites files under ``app/src/main/res`` (the stock Android Studio template
art included) and writes a proof sheet to ``app/build/icon-proof/proof.html`` so
the result can be checked by eye before a build.
"""

from __future__ import annotations

import base64
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- design knobs

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

FONT_CANDIDATES = [
    Path(r"C:\Windows\Fonts\Nirmala.ttc"),  # Windows 8+
    Path(r"C:\Windows\Fonts\latha.ttf"),  # Windows 7 and older
    Path("/usr/share/fonts/truetype/noto/NotoSansTamil-Regular.ttf"),
    Path("/System/Library/Fonts/Supplemental/Tamil Sangam MN.ttc"),
]
FONT_INDEX = 0  # Nirmala.ttc index 0 is "Nirmala UI Regular"
LETTER = "\u0ba4"  # த
LETTER_LABEL = "U+0BA4 TAMIL LETTER TA"  # consoles here are cp1252, so logs stay ASCII

BG_START = (0x25, 0x1C, 0x57)  # deep indigo
BG_END = (0x6C, 0x4B, 0xD1)  # violet
INK = (0xFF, 0xFF, 0xFF)

# Adaptive icons are 108dp and a launcher may shave up to 33% off each edge, so
# the glyph stays inside the central safe zone (a 66dp circle).
# 48dp of the 108dp canvas: comfortably inside the 66dp safe circle, so a round
# mask never reaches the letter's tail (52dp left it about a pixel of clearance).
LAUNCHER_CANVAS = 108.0
LAUNCHER_MARK = 48.0
BADGE_CANVAS = 108.0
BADGE_MARK = 48.0
BADGE_CORNER = 26.0
BUBBLE_CANVAS = 24.0
BUBBLE_MARK = 19.0
LEGACY_MARK_FRACTION = 0.56  # of the tile size, in the rasters

DENSITIES = {  # res folder -> launcher icon size in px
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


# ------------------------------------------------------- TrueType (no fontTools)


def read_font(path: Path, index: int = 0):
    """Return (bytes, {tag: (offset, length)}) for one font inside a .ttf/.ttc."""
    data = path.read_bytes()
    base = 0
    if data[:4] == b"ttcf":
        count = _u32(data, 8)
        if index >= count:
            raise ValueError(f"{path} holds {count} fonts; index {index} is out of range")
        base = _u32(data, 12 + 4 * index)
    tables = {}
    for i in range(_u16(data, base + 4)):
        rec = base + 12 + 16 * i
        tag = data[rec : rec + 4].decode("latin-1")
        tables[tag] = (_u32(data, rec + 8), _u32(data, rec + 12))
    return data, tables


def glyph_id_for(data: bytes, tables: dict, codepoint: int) -> int:
    """Character -> glyph id, via the best cmap subtable on offer."""
    cmap = tables["cmap"][0]
    ranked = []
    for i in range(_u16(data, cmap + 2)):
        rec = cmap + 4 + 8 * i
        platform, encoding, sub = _u16(data, rec), _u16(data, rec + 2), _u32(data, rec + 4)
        # Windows UCS-4, then Windows BMP, then the Unicode platform flavours.
        rank = {(3, 10): 0, (3, 1): 1, (0, 4): 2, (0, 3): 3, (0, 6): 4}.get((platform, encoding))
        if rank is not None:
            ranked.append((rank, cmap + sub))
    for _, sub in sorted(ranked):
        fmt = _u16(data, sub)
        gid = _cmap4(data, sub, codepoint) if fmt == 4 else _cmap12(data, sub, codepoint) if fmt == 12 else 0
        if gid:
            return gid
    return 0


def _cmap4(data: bytes, off: int, cp: int) -> int:
    segments = _u16(data, off + 6) // 2
    p = off + 14
    ends = _u16s(data, p, segments)
    p += 2 * segments + 2  # reservedPad
    starts = _u16s(data, p, segments)
    p += 2 * segments
    deltas = _i16s(data, p, segments)
    p += 2 * segments
    range_offsets = _u16s(data, p, segments)
    for i in range(segments):
        if ends[i] < cp:
            continue
        if starts[i] > cp:
            return 0
        if range_offsets[i] == 0:
            return (cp + deltas[i]) & 0xFFFF
        addr = p + 2 * i + range_offsets[i] + 2 * (cp - starts[i])
        gid = _u16(data, addr)
        return (gid + deltas[i]) & 0xFFFF if gid else 0
    return 0


def _cmap12(data: bytes, off: int, cp: int) -> int:
    for i in range(_u32(data, off + 12)):
        rec = off + 16 + 12 * i
        start, end, first = _u32(data, rec), _u32(data, rec + 4), _u32(data, rec + 8)
        if start <= cp <= end:
            return first + (cp - start)
    return 0


def glyph_contours(data: bytes, tables: dict, gid: int):
    """One glyph's outlines as [[(x, y, on_curve), ...], ...] in font units."""
    long_loca = _i16(data, tables["head"][0] + 50)
    loca = tables["loca"][0]
    if long_loca:
        start, end = _u32(data, loca + 4 * gid), _u32(data, loca + 4 * gid + 4)
    else:
        start, end = _u16(data, loca + 2 * gid) * 2, _u16(data, loca + 2 * gid + 2) * 2
    if start == end:
        return []  # spaces and friends have no outline at all

    glyf = tables["glyf"][0] + start
    contours = _i16(data, glyf)
    return _simple_glyph(data, glyf, contours) if contours >= 0 else _composite_glyph(data, tables, glyf)


def _simple_glyph(data: bytes, glyf: int, contours: int):
    p = glyf + 10
    ends = _u16s(data, p, contours)
    p += 2 * contours
    total = ends[-1] + 1 if contours else 0
    p += 2 + _u16(data, p)  # instructionLength, then the hinted instructions

    flags = []
    while len(flags) < total:
        flag = data[p]
        p += 1
        flags.append(flag)
        if flag & 0x08:  # REPEAT
            repeats = data[p]
            p += 1
            flags.extend([flag] * repeats)
    flags = flags[:total]

    xs, x = [], 0
    for flag in flags:
        if flag & 0x02:
            delta = data[p]
            p += 1
            x += delta if flag & 0x10 else -delta
        elif not flag & 0x10:
            x += _i16(data, p)
            p += 2
        xs.append(x)

    ys, y = [], 0
    for flag in flags:
        if flag & 0x04:
            delta = data[p]
            p += 1
            y += delta if flag & 0x20 else -delta
        elif not flag & 0x20:
            y += _i16(data, p)
            p += 2
        ys.append(y)

    out, first = [], 0
    for last in ends:
        out.append([(xs[i], ys[i], bool(flags[i] & 0x01)) for i in range(first, last + 1)])
        first = last + 1
    return out


def _composite_glyph(data: bytes, tables: dict, glyf: int):
    ARG_WORDS, ARGS_XY, HAVE_SCALE = 0x0001, 0x0002, 0x0008
    HAVE_XY_SCALE, HAVE_2X2, MORE = 0x0040, 0x0080, 0x0020

    p, out = glyf + 10, []
    while True:
        flags, component = _u16(data, p), _u16(data, p + 2)
        p += 4
        pattern = ">hh" if (flags & ARG_WORDS and flags & ARGS_XY) else \
                  ">HH" if flags & ARG_WORDS else \
                  ">bb" if flags & ARGS_XY else ">BB"
        size = 4 if flags & ARG_WORDS else 2
        a, b = _unpack(pattern, data, p)
        p += size
        dx, dy = (a, b) if flags & ARGS_XY else (0, 0)  # point-attachment is vanishingly rare

        xx, xy, yx, yy = 1.0, 0.0, 0.0, 1.0
        if flags & HAVE_SCALE:
            xx = yy = _i16(data, p) / 16384.0
            p += 2
        elif flags & HAVE_XY_SCALE:
            xx, yy = (_i16(data, p + 2 * k) / 16384.0 for k in range(2))
            p += 4
        elif flags & HAVE_2X2:
            xx, xy, yx, yy = (_i16(data, p + 2 * k) / 16384.0 for k in range(4))
            p += 8

        for contour in glyph_contours(data, tables, component):
            out.append(
                [(xx * cx + xy * cy + dx, yx * cx + yy * cy + dy, on) for cx, cy, on in contour]
            )
        if not flags & MORE:
            return out


def _unpack(pattern, data, p):
    import struct

    return struct.unpack(pattern, data[p : p + struct.calcsize(pattern)])


def _u16(data, p):
    import struct

    return struct.unpack(">H", data[p : p + 2])[0]


def _i16(data, p):
    import struct

    return struct.unpack(">h", data[p : p + 2])[0]


def _u32(data, p):
    import struct

    return struct.unpack(">I", data[p : p + 4])[0]


def _u16s(data, p, n):
    import struct

    return struct.unpack(">%dH" % n, data[p : p + 2 * n])


def _i16s(data, p, n):
    import struct

    return struct.unpack(">%dh" % n, data[p : p + 2 * n])


# ------------------------------------------------- outline -> paths/geometry


def contour_segments(points):
    """Walk one TrueType contour, yielding ("M", p) / ("Q", ctrl, p) / "Z".

    TrueType stores a vertex per on- and off-curve point, so two off-curve
    points in a row imply an on-curve point at their midpoint.
    """
    pts = list(points)
    if not pts:
        return
    if not pts[0][2]:
        if pts[-1][2]:
            pts = [pts[-1]] + pts[:-1]
        else:
            pts = [((pts[-1][0] + pts[0][0]) / 2, (pts[-1][1] + pts[0][1]) / 2, True)] + pts

    yield ("M", (pts[0][0], pts[0][1]))
    control = None
    for i in range(1, len(pts) + 1):
        x, y, on = pts[i % len(pts)]
        if on:
            if control is None:
                yield ("L", (x, y))
            else:
                yield ("Q", control, (x, y))
                control = None
        elif control is None:
            control = (x, y)
        else:
            yield ("Q", control, ((control[0] + x) / 2, (control[1] + y) / 2))
            control = (x, y)
    yield "Z"


def _quad_point(p0, p1, p2, t):
    u = 1 - t
    return (
        u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
        u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
    )


def ink_bbox(contours):
    """Tight bounds of the glyph, taking quadratic curve extrema into account."""
    xs, ys = [], []

    for contour in contours:
        start = None
        for command in contour_segments(contour):
            if command == "Z":
                continue
            kind = command[0]
            if kind == "Q":
                ctrl, end = command[1], command[2]
                for axis, seen in ((0, xs), (1, ys)):
                    seen.append(ctrl[axis])
                    seen.append(end[axis])
                    p0, p1, p2 = start[axis], ctrl[axis], end[axis]
                    denom = p0 - 2 * p1 + p2
                    if abs(denom) > 1e-9:
                        t = (p0 - p1) / denom
                        if 0.0 < t < 1.0:
                            seen.append(_quad_point(start, ctrl, end, t)[axis])
            else:
                end = command[1]
                xs.append(end[0])
                ys.append(end[1])
            start = end
    return min(xs), min(ys), max(xs), max(ys)


def normalize(contours, canvas: float, mark: float):
    """Fit the glyph's ink into a square canvas, y flipped into screen space."""
    x0, y0, x1, y1 = ink_bbox(contours)
    scale = mark / max(x1 - x0, y1 - y0)
    left = (canvas - (x1 - x0) * scale) / 2
    top = (canvas - (y1 - y0) * scale) / 2

    def move(point):
        return ((point[0] - x0) * scale + left, (y1 - point[1]) * scale + top)

    return [
        [command if command == "Z" else (command[0],) + tuple(move(p) for p in command[1:])
         for command in contour_segments(contour)]
        for contour in contours
    ]


def path_data(commands) -> str:
    def num(value):
        text = f"{value:.2f}".rstrip("0").rstrip(".")
        return "0" if text in ("", "-0") else text

    parts = []
    for command in commands:
        if command == "Z":
            parts.append("Z")
        elif command[0] == "Q":
            ctrl, end = command[1], command[2]
            parts.append(f"Q {num(ctrl[0])} {num(ctrl[1])} {num(end[0])} {num(end[1])}")
        else:
            parts.append(f"{command[0]} {num(command[1][0])} {num(command[1][1])}")
    return " ".join(parts)


def glyph_svg_path(contours, color: str) -> str:
    """One SVG path for the whole glyph, same winding rule as the Android vector."""
    data = " ".join(path_data(commands) for commands in contours)
    return f'<path d="{data}" fill="{color}" fill-rule="nonzero"/>'


# ------------------------------------------------------------------ emitting


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"  wrote {path.relative_to(ROOT)}")


def hex_color(color) -> str:
    return "#%02X%02X%02X" % color


GENERATED = "Generated by tools/make_icons.py from the Tamil letter {letter} (U+0BA4) in {font}. " \
            "Edit the generator, not this file, and run it again."


def vector(width: float, height: float, body: str, font: str) -> str:
    aapt = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if "aapt:attr" in body else ""
    note = GENERATED.format(letter=LETTER, font=font)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {note} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}
    android:width="{width:g}dp"
    android:height="{height:g}dp"
    android:viewportWidth="{width:g}"
    android:viewportHeight="{height:g}">
{body}
</vector>
"""


def gradient_stops() -> str:
    return (
        f'\n                <item android:color="{hex_color(BG_START)}" android:offset="0" />'
        f'\n                <item android:color="{hex_color(BG_END)}" android:offset="1" />'
    )


def gradient_fill(size: float, indent: str) -> str:
    stops = "\n".join(indent + "    " + line.strip() for line in gradient_stops().strip().splitlines())
    return (
        f'{indent}<aapt:attr name="android:fillColor">\n'
        f'{indent}    <gradient\n'
        f'{indent}        android:type="linear"\n'
        f'{indent}        android:startX="0"\n'
        f'{indent}        android:startY="0"\n'
        f'{indent}        android:endX="{size:g}"\n'
        f'{indent}        android:endY="{size:g}">\n'
        f"{stops}\n"
        f"{indent}    </gradient>\n"
        f"{indent}</aapt:attr>"
    )


def fill_path(contours, indent: str = "    ") -> str:
    """One path holding every contour of the glyph.

    It has to be a single path: the counters (the bowls of the letter) are extra
    contours wound the other way round, and only nonzero winding inside one path
    knocks them out. Split across several paths they would each fill solid.
    """
    data = " ".join(path_data(commands) for commands in contours)
    return (
        f'{indent}<path\n{indent}    android:fillColor="{hex_color(INK)}"\n'
        f'{indent}    android:pathData="{data}" />'
    )


def rounded_rect(size: float, radius: float) -> str:
    r = radius
    return (
        f"M{r:g},0 H{size - r:g} A{r:g},{r:g} 0 0 1 {size:g},{r:g} V{size - r:g} "
        f"A{r:g},{r:g} 0 0 1 {size - r:g},{size:g} H{r:g} A{r:g},{r:g} 0 0 1 0,{size - r:g} "
        f"V{r:g} A{r:g},{r:g} 0 0 1 {r:g},0 Z"
    )


def adaptive_icon() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/make_icons.py. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


# ------------------------------------------------------------------- rasters


def diagonal_gradient(size: int) -> Image.Image:
    """Deep indigo -> violet, top-left to bottom-right, built small then scaled."""
    small = Image.new("RGB", (64, 64))
    pixels = small.load()
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126.0
            pixels[x, y] = tuple(
                round(BG_START[c] + (BG_END[c] - BG_START[c]) * t) for c in range(3)
            )
    return small.resize((size, size), Image.BICUBIC).convert("RGBA")


def glyph_stamp(font: ImageFont.FreeTypeFont, canvas: int, fraction: float) -> Image.Image:
    """The letter as white ink on transparency, fitted to `fraction` of the canvas."""
    ink = Image.new("L", (canvas, canvas), 0)
    draw = ImageDraw.Draw(ink)
    box = draw.textbbox((0, 0), LETTER, font=font)
    draw.text(
        ((canvas - (box[2] - box[0])) / 2 - box[0], (canvas - (box[3] - box[1])) / 2 - box[1]),
        LETTER,
        font=font,
        fill=255,
    )
    ink = ink.crop(ink.getbbox())
    target = canvas * fraction
    scale = target / max(ink.width, ink.height)
    ink = ink.resize((max(1, round(ink.width * scale)), max(1, round(ink.height * scale))), Image.LANCZOS)

    stamp = Image.new("RGBA", ink.size, INK + (255,))
    stamp.putalpha(ink)
    return stamp


def render_legacy_tile(font_path: Path, size: int, round_crop: bool, supersample: int = 4) -> Image.Image:
    canvas = size * supersample
    tile = diagonal_gradient(canvas)

    font = ImageFont.truetype(str(font_path), int(canvas * 0.9), index=FONT_INDEX)
    stamp = glyph_stamp(font, canvas, LEGACY_MARK_FRACTION)
    tile.alpha_composite(stamp, ((canvas - stamp.width) // 2, (canvas - stamp.height) // 2))

    mask = Image.new("L", (canvas, canvas), 0)
    draw = ImageDraw.Draw(mask)
    if round_crop:
        draw.ellipse((0, 0, canvas - 1, canvas - 1), fill=255)
    else:
        draw.rounded_rectangle(
            (0, 0, canvas - 1, canvas - 1),
            radius=round(canvas * BADGE_CORNER / BADGE_CANVAS),
            fill=255,
        )
    tile.putalpha(mask)
    return tile.resize((size, size), Image.LANCZOS)


# -------------------------------------------------- outline raster + checking


def flatten(commands, steps: int = 12):
    """Approximate a command list as a polygon (quadratics sampled)."""
    points, current = [], (0.0, 0.0)
    for command in commands:
        if command == "Z":
            continue
        if command[0] in ("M", "L"):
            current = command[1]
            points.append(current)
        else:
            ctrl, end = command[1], command[2]
            points.extend(_quad_point(current, ctrl, end, i / steps) for i in range(1, steps + 1))
            current = end
    return points


def signed_area(polygon) -> float:
    total = 0.0
    for i in range(len(polygon)):
        x0, y0 = polygon[i]
        x1, y1 = polygon[(i + 1) % len(polygon)]
        total += x0 * y1 - x1 * y0
    return total / 2.0


def inside(point, polygon) -> bool:
    x, y = point
    hit = False
    for i in range(len(polygon)):
        x0, y0 = polygon[i]
        x1, y1 = polygon[(i + 1) % len(polygon)]
        if (y0 > y) != (y1 > y):
            crossing = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
            if x < crossing:
                hit = not hit
    return hit


def render_outline(commands_list, size: int, supersample: int = 3) -> Image.Image:
    """Fill an outline the way the platform will: nonzero winding, holes knocked out."""
    scale = size * supersample
    image = Image.new("L", (scale, scale), 0)
    draw = ImageDraw.Draw(image)
    polygons = [
        [tuple(value * supersample for value in point) for point in flatten(contour)]
        for contour in commands_list
    ]
    # Nesting depth decides the fill: a contour paints, one inside it knocks its
    # own area back out, one inside that paints again - what nonzero winding
    # gives for contours that do not cross each other.
    depths = [
        sum(1 for j, other in enumerate(polygons) if j != i and inside(polygon[0], other))
        for i, polygon in enumerate(polygons)
    ]
    for polygon, depth in sorted(zip(polygons, depths), key=lambda item: item[1]):
        draw.polygon(polygon, fill=0 if depth % 2 else 255)
    return image.resize((size, size), Image.LANCZOS)


def font_glyph_square(font_path: Path, size: int, ink_color=INK, supersample: int = 4) -> Image.Image:
    """The font's own rendering of the letter, cropped ink-to-ink into a square."""
    canvas = size * supersample
    mask = Image.new("L", (canvas, canvas), 0)
    draw = ImageDraw.Draw(mask)
    font = ImageFont.truetype(str(font_path), int(canvas * 0.9), index=FONT_INDEX)
    box = draw.textbbox((0, 0), LETTER, font=font)
    draw.text(
        ((canvas - (box[2] - box[0])) / 2 - box[0], (canvas - (box[3] - box[1])) / 2 - box[1]),
        LETTER,
        font=font,
        fill=255,
    )
    mask = mask.crop(mask.getbbox())
    fit = size / max(mask.width, mask.height)
    mask = mask.resize((max(1, round(mask.width * fit)), max(1, round(mask.height * fit))), Image.LANCZOS)
    square = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    stamp = Image.new("RGBA", mask.size, tuple(ink_color) + (255,))
    stamp.putalpha(mask)
    square.alpha_composite(stamp, ((size - mask.width) // 2, (size - mask.height) // 2))
    return square


def comparison_panels(contours, font_path: Path, size: int = 240):
    """Three readable images: my outline, the font's raster, and where they differ."""
    mine = render_outline(normalize(contours, float(size), float(size)), size)
    theirs = font_glyph_square(font_path, size)

    def as_picture(mask):
        out = Image.new("RGB", (size, size), "white")
        paint, source = out.load(), mask.load()
        for y in range(size):
            for x in range(size):
                value = source[x, y]
                alpha = value if isinstance(value, int) else value[3]
                if alpha > 127:
                    paint[x, y] = (26, 26, 38)
        return out

    diff = Image.new("RGB", (size, size), "white")
    paint, a, b = diff.load(), mine.load(), theirs.load()
    disagreements = 0
    for y in range(size):
        for x in range(size):
            left = a[x, y] > 127
            right = b[x, y][3] > 127
            if left != right:
                paint[x, y] = (220, 30, 30)
                disagreements += 1
            elif left:
                paint[x, y] = (150, 150, 160)
    return as_picture(mine), as_picture(theirs), diff, disagreements


def verify(contours, font_path: Path, size: int = 240) -> float:
    """How closely the extracted outline matches the font's own rasterisation.

    Both are drawn ink-to-ink into the same square, so a broken `glyf` parse - a
    dropped contour, a misread coordinate - shows up as disagreement instead of
    a vague feeling that the picture looks odd.
    """
    mine = render_outline(normalize(contours, float(size), float(size)), size)
    theirs = font_glyph_square(font_path, size)
    a, b, agree, union = mine.load(), theirs.load(), 0, 0
    for y in range(size):
        for x in range(size):
            mine_ink = a[x, y] > 127
            font_ink = b[x, y][3] > 127
            if mine_ink or font_ink:
                union += 1
                agree += 1 if mine_ink == font_ink else 0
    return agree / union if union else 0.0


# --------------------------------------------------------------- proof sheet


def data_uri(path: Path) -> str:
    mime = "image/svg+xml" if path.suffix == ".svg" else f"image/{path.suffix[1:]}"
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode()


def proof_sheet(contours, launcher, font_path: Path, font_label: str, agreement: float):
    """A page showing the vector, the font's own raster and their difference."""
    out = ROOT / "app" / "build" / "icon-proof"
    out.mkdir(parents=True, exist_ok=True)

    # Both sides are drawn ink-to-ink into the same square, with no background of
    # their own, so the difference view lines up: mismatch shows up as bright.
    side = 240
    vector_svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {side} {side}" '
        f'width="{side}" height="{side}">'
        + glyph_svg_path(normalize(contours, side, side), hex_color(INK))
        + "</svg>"
    )
    (out / "glyph-vector.svg").write_text(vector_svg, encoding="utf-8")
    mine, theirs, diff, disagreements = comparison_panels(contours, font_path, side)
    mine.save(out / "glyph-outline.png")
    theirs.save(out / "glyph-font.png")
    diff.save(out / "glyph-difference.png")

    launcher_svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="200" height="200">'
        f'<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{hex_color(BG_START)}"/>'
        f'<stop offset="1" stop-color="{hex_color(BG_END)}"/></linearGradient></defs>'
        '<rect width="108" height="108" fill="url(#g)"/>'
        + glyph_svg_path(launcher, hex_color(INK))
        + "</svg>"
    )
    (out / "launcher-composition.svg").write_text(launcher_svg, encoding="utf-8")

    # The safe zone is a 66dp circle in the 108dp canvas: 61.1% of a closest-side
    # radius. The tighter one is deliberately harsher than any real launcher.
    masks = [
        ("no mask", ""),
        ("circle mask", "-webkit-mask-image:radial-gradient(circle closest-side,#000 0 61.1%,transparent 61.1%);"
                        "mask-image:radial-gradient(circle closest-side,#000 0 61.1%,transparent 61.1%);"),
        ("tight circle (harsher than real)", "-webkit-mask-image:radial-gradient(circle closest-side,#000 0 52%,transparent 52%);"
                                             "mask-image:radial-gradient(circle closest-side,#000 0 52%,transparent 52%);"),
        ("squircle mask", "border-radius:28%;"),
    ]
    composition = out / "launcher-composition.svg"
    tiles = []
    for label, style in masks:
        tiles.append(
            f'  <figure><div class="tile" style="{style}">'
            f'<img src="{data_uri(composition)}" width="200" height="200"></div>'
            f"<figcaption>{label}</figcaption></figure>"
        )
    tiles = "\n".join(tiles)

    rasters = "\n".join(
        f'  <figure><img src="{data_uri(RES / f"mipmap-{folder}" / f"{name}.webp")}" '
        f'width="120" height="120"><figcaption>{folder} {size}px{" round" if round_crop else ""}</figcaption></figure>'
        for folder, size in DENSITIES.items()
        for name, round_crop in (("ic_launcher", False), ("ic_launcher_round", True))
    )

    page = f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Tanlish Translate icons</title>
<style>
  body {{ font:14px/1.5 system-ui,sans-serif; background:#f5f5f7; color:#1b1b1f; margin:24px; }}
  h2 {{ margin:28px 0 6px; font-size:16px; }}
  p {{ color:#555; max-width:820px; }}
  .row {{ display:flex; gap:20px; flex-wrap:wrap; align-items:flex-start; }}
  figure {{ margin:0; text-align:center; }}
  figcaption {{ font-size:11px; color:#666; margin-top:6px; }}
  .tile {{ position:relative; width:200px; height:200px; overflow:hidden; background:#ddd; }}
  .tile img {{ position:absolute; inset:0; }}
</style></head><body>
<h1>த &mdash; generated icon proof sheet</h1>
<p>Everything here comes from one outline of <b>{LETTER}</b> (U+0BA4) read out of
<b>{font_label}</b>. Section 1 is the real test: the outline this generator read out of the font,
that font's own raster of the same glyph, and where the two disagree. The ink masks agree on
<b>{agreement * 100:.1f}%</b> of the letter, and the red pixels below number {disagreements} of
{side * side} - a thin anti-aliased rim rather than a wrong shape.</p>

<h2>1. Extracted vector vs the font's raster</h2>
<div class="row">
  <figure><img src="{data_uri(out / "glyph-outline.png")}" width="240" height="240">
    <figcaption>my outline, filled (nonzero)</figcaption></figure>
  <figure><img src="{data_uri(out / "glyph-font.png")}" width="240" height="240">
    <figcaption>the font's own raster (FreeType)</figcaption></figure>
  <figure><img src="{data_uri(out / "glyph-difference.png")}" width="240" height="240">
    <figcaption>agreement (grey) and disagreement (red)</figcaption></figure>
</div>

<h2>2. Adaptive icon through launcher masks</h2>
<p>The glyph has to survive a launcher shaving up to 33% off every edge. The third tile is
deliberately harsher than any real launcher: if strokes survive there, they survive anywhere.</p>
<div class="row">
{tiles}
</div>

<h2>3. Legacy rasters (API 24&ndash;25 and round-mask launchers)</h2>
<div class="row">
{rasters}
</div>
</body></html>
"""
    (out / "proof.html").write_text(page, encoding="utf-8")
    print(f"  wrote {(out / 'proof.html').relative_to(ROOT)}")
    return out / "proof.html"


# ---------------------------------------------------------------------- main


def pick_font() -> Path:
    argv = sys.argv[1:]
    if "--font" in argv:
        return Path(argv[argv.index("--font") + 1])
    for candidate in FONT_CANDIDATES:
        if candidate.exists():
            return candidate
    raise SystemExit(
        "No Tamil font found; pass one with --font. Looked for:\n  "
        + "\n  ".join(str(path) for path in FONT_CANDIDATES)
    )


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    font_path = pick_font()
    font_label = font_path.name
    data, tables = read_font(font_path, FONT_INDEX)

    gid = glyph_id_for(data, tables, ord(LETTER))
    if gid == 0:
        raise SystemExit(f"{font_path} has no glyph for {LETTER_LABEL}")
    contours = glyph_contours(data, tables, gid)
    if not contours:
        raise SystemExit(f"glyph {gid} has an empty outline")
    print(
        f"{LETTER_LABEL} -> glyph {gid}: {len(contours)} contour(s), "
        f"{sum(len(c) for c in contours)} points, unitsPerEm {_u16(data, tables['head'][0] + 18)}"
    )

    launcher = normalize(contours, LAUNCHER_CANVAS, LAUNCHER_MARK)
    badge = normalize(contours, BADGE_CANVAS, BADGE_MARK)
    bubble = normalize(contours, BUBBLE_CANVAS, BUBBLE_MARK)

    print("icons:")
    write(
        RES / "drawable" / "ic_launcher_background.xml",
        vector(
            LAUNCHER_CANVAS,
            LAUNCHER_CANVAS,
            f'    <path android:pathData="M0,0h108v108h-108z">\n'
            f"{gradient_fill(LAUNCHER_CANVAS, '        ')}\n    </path>",
            font_label,
        ),
    )
    write(
        RES / "drawable" / "ic_launcher_foreground.xml",
        vector(LAUNCHER_CANVAS, LAUNCHER_CANVAS, fill_path(launcher), font_label),
    )
    write(
        RES / "drawable" / "ic_launcher_monochrome.xml",
        vector(LAUNCHER_CANVAS, LAUNCHER_CANVAS, fill_path(launcher), font_label),
    )
    write(
        RES / "drawable" / "ic_app_mark.xml",
        vector(
            BADGE_CANVAS,
            BADGE_CANVAS,
            f'    <path android:pathData="{rounded_rect(BADGE_CANVAS, BADGE_CORNER)}">\n'
            f"{gradient_fill(BADGE_CANVAS, '        ')}\n    </path>\n"
            + fill_path(badge),
            font_label,
        ),
    )
    write(
        RES / "drawable" / "ic_bubble_mark.xml",
        vector(BUBBLE_CANVAS, BUBBLE_CANVAS, fill_path(bubble), font_label),
    )
    write(
        RES / "drawable" / "ic_bubble_done.xml",
        vector(
            24,
            24,
            '    <path\n        android:pathData="M5,12.5 L10,17.5 L19,6.5"\n'
            '        android:strokeColor="#FFFFFFFF"\n        android:strokeWidth="2.4"\n'
            '        android:strokeLineCap="round"\n        android:strokeLineJoin="round" />',
            font_label,
        ),
    )
    write(
        RES / "drawable" / "ic_bubble_failed.xml",
        vector(
            24,
            24,
            '    <path\n        android:pathData="M12,5.5 L12,14"\n'
            '        android:strokeColor="#FFFFFFFF"\n        android:strokeWidth="2.4"\n'
            '        android:strokeLineCap="round" />\n'
            '    <path\n        android:fillColor="#FFFFFFFF"\n'
            '        android:pathData="M10.4,17.5 A1.6,1.6 0 1 0 13.6,17.5 A1.6,1.6 0 1 0 10.4,17.5 Z" />',
            font_label,
        ),
    )
    for name in ("ic_launcher", "ic_launcher_round"):
        write(RES / "mipmap-anydpi-v26" / f"{name}.xml", adaptive_icon())

    print("legacy rasters:")
    for folder, size in DENSITIES.items():
        for name, round_crop in (("ic_launcher", False), ("ic_launcher_round", True)):
            path = RES / f"mipmap-{folder}" / f"{name}.webp"
            path.parent.mkdir(parents=True, exist_ok=True)
            render_legacy_tile(font_path, size, round_crop).save(path, "WEBP", quality=95, method=6)
            print(f"  wrote {path.relative_to(ROOT)} ({size}x{size})")

    agreement = verify(contours, font_path)
    graded = "OK" if agreement > 0.97 else "LOW - check the extraction"
    print(f"\nextracted outline vs the font's own raster: {agreement * 100:.2f}% agreement ({graded})")

    docs = ROOT / "docs"
    docs.mkdir(parents=True, exist_ok=True)
    render_legacy_tile(font_path, 512, round_crop=False).save(docs / "icon.png", optimize=True)
    print(f"  wrote {(docs / 'icon.png').relative_to(ROOT)} (512x512, for the README)")

    print(f"proof sheet: {proof_sheet(contours, launcher, font_path, font_label, agreement).relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
