# 生成 LocalChat 应用图标 app.ico(纯标准库,无第三方依赖)
# 设计:黑色圆角方块 + 白色聊天气泡(带尾巴)+ 三个思考点,与应用内黑白风格一致
import math
import struct
import zlib
import os

SIZE = 256
SS = 4  # 每像素超采样次数(抗锯齿)

BG = (0x1C, 0x1C, 0x1E, 255)
BUBBLE = (255, 255, 255, 255)
DOT = (0x1C, 0x1C, 0x1E, 255)


def clampf(v, a, b):
    return max(a, min(b, v))


def sd_rounded_rect(x, y, cx, cy, hw, hh, r):
    dx = abs(x - cx) - (hw - r)
    dy = abs(y - cy) - (hh - r)
    ox, oy = max(dx, 0.0), max(dy, 0.0)
    return math.hypot(ox, oy) + min(max(dx, dy), 0.0) - r


def sd_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) - r


def sd_triangle(x, y, p0, p1, p2):
    """三角形有向距离:内部为负。叉积判内 + 到三边的最短距离。"""
    def cross(ax, ay, bx, by, px, py):
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    d1 = cross(*p0, *p1, x, y)
    d2 = cross(*p1, *p2, x, y)
    d3 = cross(*p2, *p0, x, y)
    inside = (d1 >= 0 and d2 >= 0 and d3 >= 0) or (d1 <= 0 and d2 <= 0 and d3 <= 0)

    def seg_dist(ax, ay, bx, by):
        vx, vy = x - ax, y - ay
        ex, ey = bx - ax, by - ay
        t = clampf((vx * ex + vy * ey) / (ex * ex + ey * ey), 0.0, 1.0)
        return math.hypot(vx - ex * t, vy - ey * t)

    d = min(seg_dist(*p0, *p1), seg_dist(*p1, *p2), seg_dist(*p2, *p0))
    return -d if inside else d


def coverage(sdf, size, with_dots):
    """返回 size x size 的 alpha 覆盖率字典列表"""
    cov = {}
    k = SIZE / size
    for j in range(size):
        for i in range(size):
            c = 0
            for sj in range(SS):
                for si in range(SS):
                    x = (i + (si + 0.5) / SS) * k
                    y = (j + (sj + 0.5) / SS) * k
                    if sdf(x, y) < 0:
                        c += 1
            cov[(i, j)] = c / (SS * SS)
    return cov


def over(dst, src, a_dst, a_src):
    if a_src >= 1:
        return src, 1.0
    if a_src <= 0:
        return dst, a_dst
    a = a_src + a_dst * (1 - a_src)
    if a <= 0:
        return (0, 0, 0), 0.0
    c = tuple((s * a_src + d * a_dst * (1 - a_src)) / a for s, d in zip(src, dst))
    return c, a


def dot_radius(size):
    """小尺寸按比例放大圆点半径,保证三个点清晰可见"""
    if size >= 128:
        return 10.5
    if size >= 64:
        return 11.0
    if size >= 48:
        return 12.0
    if size >= 32:
        return 15.0
    if size >= 24:
        return 20.0
    return 28.0


def render(size):
    k = SIZE / size

    bg_sdf = lambda x, y: sd_rounded_rect(x, y, 128, 128, 116, 116, 52)
    bubble_sdf = lambda x, y: min(
        sd_rounded_rect(x, y, 128, 116, 72, 56, 28),
        sd_triangle(x, y, (88, 150), (64, 200), (124, 158)),
    )
    r = dot_radius(size)
    dot_sdf = lambda x, y: min(
        sd_circle(x, y, 100, 116, r),
        sd_circle(x, y, 128, 116, r),
        sd_circle(x, y, 156, 116, r),
    )

    a_bg = coverage(bg_sdf, size, True)
    a_bubble = coverage(bubble_sdf, size, True)
    a_dot = coverage(dot_sdf, size, True)

    pixels = []
    for j in range(size):
        row = []
        for i in range(size):
            c, a = (0, 0, 0), 0.0
            c, a = over(c, BG, a, a_bg[(i, j)])
            c, a = over(c, BUBBLE, a, a_bubble[(i, j)])
            c, a = over(c, DOT, a, a_dot[(i, j)])
            row.append((round(c[0]), round(c[1]), round(c[2]), round(a * 255)))
        pixels.append(row)
    return pixels


def png_encode(size, pixels):
    raw = b""
    for j in range(size):
        raw += b"\x00"
        for i in range(size):
            raw += bytes(pixels[j][i])

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def bmp_encode(size, pixels):
    xor = b""
    for j in range(size - 1, -1, -1):  # 自底向上
        for i in range(size):
            r, g, b, a = pixels[j][i]
            xor += bytes((b, g, r, a))
    mask_row = ((size + 31) // 32) * 4
    and_mask = b"\x00" * (mask_row * size)
    bih = struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0,
                      len(xor) + len(and_mask), 0, 0, 0, 0)
    return bih + xor + and_mask


def main():
    entries = []  # (size, is_png, data)
    for size in (256, 128, 64, 48, 32, 24, 16):
        pixels = render(size)
        if size == 256:
            entries.append((size, True, png_encode(size, pixels)))
        else:
            entries.append((size, False, bmp_encode(size, pixels)))
        print(f"  {size}px 完成")

    out = struct.pack("<HHH", 0, 1, len(entries))
    offset = 6 + 16 * len(entries)
    body = b""
    for size, is_png, data in entries:
        dim = 0 if size >= 256 else size
        out += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        body += data
        offset += len(data)

    path = os.path.join(os.path.dirname(__file__), "..", "app.ico")
    with open(path, "wb") as f:
        f.write(out + body)
    print(f"已生成 {os.path.abspath(path)}")


if __name__ == "__main__":
    main()
