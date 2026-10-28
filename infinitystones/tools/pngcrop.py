"""裁剪并放大 PrintWindow 截出的 PNG（只支持本工具自己写的 filter-0 RGBA 图）。"""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = None
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", chunk[:10])
            assert depth == 8 and ctype == 6, (depth, ctype)
        elif tag == b"IDAT":
            idat += chunk
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = w * 4
    rows = []
    for y in range(h):
        off = y * (stride + 1)
        assert raw[off] == 0
        rows.append(raw[off + 1:off + 1 + stride])
    return w, h, rows


def write_png(path, w, h, rows):
    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + r for r in rows)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    open(path, "wb").write(png)


def main():
    src, dst, x0, y0, x1, y1, scale = sys.argv[1:8]
    x0, y0, x1, y1, scale = int(x0), int(y0), int(x1), int(y1), int(scale)
    w, h, rows = read_png(src)
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w, x1), min(h, y1)
    out = []
    for y in range(y0, y1):
        row = rows[y]
        pixels = [row[x * 4:(x + 1) * 4] for x in range(x0, x1)]
        zoomed = b"".join(p * scale for p in pixels)
        for _ in range(scale):
            out.append(zoomed)
    write_png(dst, (x1 - x0) * scale, (y1 - y0) * scale, out)
    print("saved %s  (%dx%d -> x%d)" % (dst, x1 - x0, y1 - y0, scale))


if __name__ == "__main__":
    main()
