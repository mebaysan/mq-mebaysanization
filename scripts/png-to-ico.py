#!/usr/bin/env python3
"""Convert a PNG into a multi-size Windows .ico — dependency-free (no Pillow / ImageMagick).

Use this to turn your own square PNG logo into the icon jpackage embeds in the .exe.

    python3 scripts/png-to-ico.py my-logo.png            # -> scripts/app-icon.ico
    python3 scripts/png-to-ico.py my-logo.png out.ico

Then package as usual; package-windows.ps1 defaults to scripts/app-icon.ico, or pass -Icon <path>.

Tips for the source PNG:
  - Square (e.g. 256x256 or larger). Non-square is letterboxed to a square with transparency.
  - Transparency is preserved.
  - Supports 8-bit greyscale / RGB / RGBA and 1/2/4/8-bit palette PNGs, non-interlaced (the default from
    every common editor). If yours is 16-bit or interlaced, re-export as 8-bit, non-interlaced.
"""

import struct
import sys
import zlib
from pathlib import Path

ICON_SIZES = [16, 32, 48, 64, 128, 256]


# --------------------------------------------------------------------------- decode
def decode_png(data: bytes):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("Not a PNG file.")
    pos = 8
    width = height = bitd = colort = interlace = 0
    idat = bytearray()
    palette = None
    trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, bitd, colort, _comp, _filt, interlace = struct.unpack(">IIBBBBB", chunk)
        elif tag == b"PLTE":
            palette = chunk
        elif tag == b"tRNS":
            trns = chunk
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
    if interlace != 0:
        raise SystemExit("Interlaced PNG not supported — re-export without interlacing.")
    if bitd == 16:
        raise SystemExit("16-bit PNG not supported — re-export as 8-bit.")

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colort]
    raw = zlib.decompress(bytes(idat))
    bpp = max(1, channels * bitd // 8)  # bytes per pixel for the filter (>=1)
    stride = (width * channels * bitd + 7) // 8

    # undo the per-scanline filters
    out = bytearray()
    prev = bytearray(stride)
    p = 0
    for _y in range(height):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            x = line[i]
            if ft == 1:
                x += a
            elif ft == 2:
                x += b
            elif ft == 3:
                x += (a + b) >> 1
            elif ft == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                x += a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
            line[i] = x & 0xFF
        out += line
        prev = line

    # expand to RGBA
    rgba = [[(0, 0, 0, 0)] * width for _ in range(height)]

    def sample_bits(row_bytes, index):
        # for bit depths < 8 (palette/grey), read the index-th sample
        per = 8 // bitd
        byte = row_bytes[index // per]
        shift = (per - 1 - (index % per)) * bitd
        return (byte >> shift) & ((1 << bitd) - 1)

    for y in range(height):
        row = out[y * stride:(y + 1) * stride]
        for x in range(width):
            if colort == 6:
                o = x * 4; rgba[y][x] = (row[o], row[o + 1], row[o + 2], row[o + 3])
            elif colort == 2:
                o = x * 3; rgba[y][x] = (row[o], row[o + 1], row[o + 2], 255)
            elif colort == 0:
                v = row[x] if bitd == 8 else sample_bits(row, x) * (255 // ((1 << bitd) - 1))
                rgba[y][x] = (v, v, v, 255)
            elif colort == 4:
                o = x * 2; g = row[o]; rgba[y][x] = (g, g, g, row[o + 1])
            elif colort == 3:
                idx = row[x] if bitd == 8 else sample_bits(row, x)
                r, g, b = palette[idx * 3], palette[idx * 3 + 1], palette[idx * 3 + 2]
                a = trns[idx] if (trns and idx < len(trns)) else 255
                rgba[y][x] = (r, g, b, a)
    return width, height, rgba


# --------------------------------------------------------------------------- resample
def to_square(w, h, px, mode="pad"):
    if w == h:
        return w, px
    if mode == "crop":
        # Centre-crop to the smaller side — the image fills the whole icon, no transparent bars.
        n = min(w, h)
        ox, oy = (w - n) // 2, (h - n) // 2
        return n, [[px[oy + y][ox + x] for x in range(n)] for y in range(n)]
    # pad: letterbox to the larger side with transparency — the whole image is kept.
    n = max(w, h)
    out = [[(0, 0, 0, 0)] * n for _ in range(n)]
    ox, oy = (n - w) // 2, (n - h) // 2
    for y in range(h):
        for x in range(w):
            out[oy + y][ox + x] = px[y][x]
    return n, out


def resize(src_n, src, dst_n):
    if dst_n == src_n:
        return src
    out = [[(0, 0, 0, 0)] * dst_n for _ in range(dst_n)]
    if dst_n < src_n:  # area-average downscale (crisp, no library needed)
        for dy in range(dst_n):
            y0 = dy * src_n // dst_n
            y1 = max(y0 + 1, (dy + 1) * src_n // dst_n)
            for dx in range(dst_n):
                x0 = dx * src_n // dst_n
                x1 = max(x0 + 1, (dx + 1) * src_n // dst_n)
                r = g = b = a = cnt = 0
                for yy in range(y0, y1):
                    for xx in range(x0, x1):
                        pr, pg, pb, pa = src[yy][xx]
                        r += pr * pa; g += pg * pa; b += pb * pa; a += pa; cnt += 1
                if a:
                    out[dy][dx] = (r // a, g // a, b // a, a // cnt)
    else:  # nearest upscale
        for dy in range(dst_n):
            for dx in range(dst_n):
                out[dy][dx] = src[dy * src_n // dst_n][dx * src_n // dst_n]
    return out


# --------------------------------------------------------------------------- encode
def png_bytes(n, px):
    raw = bytearray()
    for y in range(n):
        raw.append(0)
        for x in range(n):
            raw.extend(px[y][x])

    def chunk(tag, d):
        return struct.pack(">I", len(d)) + tag + d + struct.pack(">I", zlib.crc32(tag + d) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def ico_bytes(frames):
    count = len(frames)
    header = struct.pack("<HHH", 0, 1, count)
    entries = b""; blob = b""; offset = 6 + count * 16
    for size, d in frames:
        w = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(d), offset)
        offset += len(d); blob += d
    return header + entries + blob


def main():
    args = [a for a in sys.argv[1:] if a != "--crop"]
    mode = "crop" if "--crop" in sys.argv else "pad"
    if not args:
        raise SystemExit("usage: python3 png-to-ico.py <source.png> [out.ico] [--crop]")
    src_path = Path(args[0])
    out_path = Path(args[1]) if len(args) > 1 else src_path.resolve().parent / "app-icon.ico"
    w, h, px = decode_png(src_path.read_bytes())
    n, sq = to_square(w, h, px, mode)
    frames = [(s, png_bytes(s, resize(n, sq, s))) for s in ICON_SIZES]
    out_path.write_bytes(ico_bytes(frames))
    print(f"wrote {out_path}  (from {w}x{h} PNG, sizes {ICON_SIZES})")


if __name__ == "__main__":
    main()
