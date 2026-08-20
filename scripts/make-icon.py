#!/usr/bin/env python3
"""Generate an 8-bit / pixel-art app icon as a multi-size Windows .ico (plus a PNG preview).

Dependency-free on purpose: it encodes PNG (zlib) and packs the .ico container by hand, so it runs on a
plain Python with no Pillow / ImageMagick. The art is authored on a 16x16 grid and scaled up by whole
factors (2x, 3x, 4x, 8x, 16x) with nearest-neighbour, which keeps the pixel edges crisp at every size —
that is what makes it read as 8-bit rather than a blurred small logo.

Motif: an indigo rounded badge (brand colour), a white envelope (a message), and a violet dot (the
accent colour) as a "you have queued messages" marker.

Run:  python3 scripts/make-icon.py
Out:  scripts/app-icon.ico   and   scripts/app-icon-preview.png
"""

import struct
import zlib
from pathlib import Path

# ---- palette (RGBA) ---------------------------------------------------------
T = (0, 0, 0, 0)                 # transparent
DARK = (55, 48, 163, 255)        # indigo-800  – badge outline
IND = (79, 70, 229, 255)         # brand indigo – badge fill
HI = (129, 140, 248, 255)        # indigo-400  – top-left highlight
WHT = (246, 248, 253, 255)       # near-white  – envelope
LN = (165, 180, 252, 255)        # indigo-300  – envelope fold lines
ACC = (139, 92, 246, 255)        # violet-500  – accent dot
ACCL = (237, 233, 254, 255)      # violet-100  – accent dot centre

SIZE = 16
grid = [[T for _ in range(SIZE)] for _ in range(SIZE)]


def put(x, y, c):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        grid[y][x] = c


def corner_cut(x, y):
    # A small rounded corner: drop the outermost corner triangle (3 px per corner).
    return (min(x, SIZE - 1 - x) + min(y, SIZE - 1 - y)) < 2


def in_badge(x, y):
    return 0 <= x < SIZE and 0 <= y < SIZE and not corner_cut(x, y)


# ---- badge: fill, then a 1px dark outline where it meets the outside --------
for y in range(SIZE):
    for x in range(SIZE):
        if in_badge(x, y):
            put(x, y, IND)
for y in range(SIZE):
    for x in range(SIZE):
        if in_badge(x, y):
            if any(not in_badge(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                put(x, y, DARK)

# subtle top-left highlight, for a touch of 8-bit shading
put(2, 2, HI)
put(3, 2, HI)
put(2, 3, HI)

# ---- envelope (white body) --------------------------------------------------
EX0, EX1, EY0, EY1 = 3, 12, 6, 11
for y in range(EY0, EY1 + 1):
    for x in range(EX0, EX1 + 1):
        put(x, y, WHT)


def line(x0, y0, x1, y1, c):
    dx = abs(x1 - x0)
    dy = -abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx + dy
    while True:
        put(x0, y0, c)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


# envelope flap: a V fold from the two top corners down to the middle
cx = (EX0 + EX1) // 2
line(EX0, EY0, cx, EY0 + 3, LN)
line(EX1, EY0, cx + 1, EY0 + 3, LN)

# ---- accent dot (top-right) -------------------------------------------------
DCX, DCY, R = 12, 4, 2
for y in range(SIZE):
    for x in range(SIZE):
        if (x - DCX) ** 2 + (y - DCY) ** 2 <= R * R:
            put(x, y, ACC)
put(DCX, DCY, ACCL)

# =============================================================================
# encoding: PNG (per size) + ICO container
# =============================================================================


def scale(px, factor):
    n = SIZE * factor
    out = [[T for _ in range(n)] for _ in range(n)]
    for y in range(n):
        for x in range(n):
            out[y][x] = px[y // factor][x // factor]
    return n, out


def png_bytes(n, px):
    raw = bytearray()
    for y in range(n):
        raw.append(0)  # filter type 0 (none)
        for x in range(n):
            raw.extend(px[y][x])

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def ico_bytes(frames):
    # frames: list of (size, png_bytes). Windows Vista+ reads PNG-compressed frames.
    count = len(frames)
    header = struct.pack("<HHH", 0, 1, count)
    entries = b""
    offset = 6 + count * 16
    blob = b""
    for size, data in frames:
        w = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
        blob += data
    return header + entries + blob


here = Path(__file__).resolve().parent
sizes = [16, 32, 48, 64, 128, 256]
frames = []
for s in sizes:
    factor = s // SIZE
    n, px = scale(grid, factor)
    frames.append((s, png_bytes(n, px)))

(here / "app-icon.ico").write_bytes(ico_bytes(frames))
# a 256px preview to eyeball the result
(here / "app-icon-preview.png").write_bytes(frames[-1][1])
print("wrote", here / "app-icon.ico", "and app-icon-preview.png")
