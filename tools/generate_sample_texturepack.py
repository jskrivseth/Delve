"""Generates a sample texture pack in modern Minecraft layout.

The pack is deliberately vivid and flat-shaded so a swap is obvious at a glance,
and it exercises the per-block file path rather than a single atlas.
"""
import math, os, struct, zlib

T = 16


def png(path, w, h, px):
    raw = b''
    for y in range(h):
        raw += b'\x00'
        for x in range(w):
            raw += struct.pack('BBBB', *px[y * w + x])

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    out = b'\x89PNG\r\n\x1a\n'
    out += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    out += chunk(b'IDAT', zlib.compress(raw, 9))
    out += chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(out)


def noise(x, y, salt):
    h = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    h &= 0xFFFFFFFF
    h ^= (h >> 13)
    h = (h * 1274126177) & 0xFFFFFFFF
    h ^= (h >> 16)
    return (h & 0xFF) / 255.0


def clamp(v):
    return max(0, min(255, int(v)))


def flat(base, salt, jitter=26, alpha=255):
    """A flat colour with light per-texel jitter."""
    px = []
    for y in range(T):
        for x in range(T):
            n = noise(x, y, salt) - 0.5
            px.append((clamp(base[0] + n * jitter),
                       clamp(base[1] + n * jitter),
                       clamp(base[2] + n * jitter), alpha))
    return px


def bordered(base, edge, salt):
    """Flat colour with a darker one-texel border, reading as a tile."""
    px = flat(base, salt)
    out = []
    for y in range(T):
        for x in range(T):
            if x == 0 or y == 0 or x == T - 1 or y == T - 1:
                out.append((edge[0], edge[1], edge[2], 255))
            else:
                out.append(px[y * T + x])
    return out


def striped(a, b, salt, period=4, vertical=False):
    px = []
    for y in range(T):
        for x in range(T):
            k = (x if vertical else y) % period
            base = a if k < period // 2 else b
            n = noise(x, y, salt) - 0.5
            px.append((clamp(base[0] + n * 18),
                       clamp(base[1] + n * 18),
                       clamp(base[2] + n * 18), 255))
    return px


def leafy(base, salt):
    """Foliage with real holes, to exercise the alpha cutout path."""
    px = []
    for y in range(T):
        for x in range(T):
            n = noise(x, y, salt)
            if n < 0.22:
                px.append((0, 0, 0, 0))
            else:
                px.append((clamp(base[0] + (n - 0.5) * 60),
                           clamp(base[1] + (n - 0.5) * 60),
                           clamp(base[2] + (n - 0.5) * 60), 255))
    return px


def glassy(base, salt):
    px = []
    for y in range(T):
        for x in range(T):
            if x in (0, T - 1) or y in (0, T - 1) or x == y:
                px.append((base[0], base[1], base[2], 255))
            else:
                px.append((0, 0, 0, 0))
    return px


ROOT = os.path.join('texturepacks', 'Vivid', 'assets', 'minecraft', 'textures', 'block')

blocks = {
    'grass_block_top':  flat((66, 214, 96), 1),
    'grass_block_side': striped((66, 214, 96), (140, 92, 54), 2, period=8),
    'dirt':             flat((146, 96, 56), 3),
    'stone':            bordered((150, 152, 160), (108, 110, 118), 4),
    'cobblestone':      striped((132, 134, 142), (96, 98, 106), 5, period=4, vertical=True),
    'sand':             flat((238, 224, 160), 6),
    'gravel':           flat((162, 156, 150), 7, jitter=70),
    'oak_log':          striped((122, 84, 48), (92, 62, 34), 8, period=4, vertical=True),
    'oak_log_top':      bordered((166, 124, 74), (110, 78, 44), 9),
    'oak_planks':       striped((198, 152, 92), (176, 132, 78), 10, period=8),
    'oak_leaves':       leafy((48, 168, 72), 11),
    'bricks':           striped((196, 92, 74), (150, 66, 52), 12, period=4),
    'bedrock':          flat((44, 44, 52), 13, jitter=60),
    'glass':            glassy((206, 236, 250), 14),
    'snow':             flat((246, 250, 255), 15, jitter=12),
    'grass_block_snow': striped((246, 250, 255), (150, 160, 172), 16, period=10),
    'water_still':      flat((54, 118, 226), 17, jitter=30),
}

for name, pixels in blocks.items():
    png(os.path.join(ROOT, name + '.png'), T, T, pixels)

# A distinctly different sun and moon, to prove those swap too.
S = 32
sun = []
for y in range(S):
    for x in range(S):
        n = noise(x // 2, y // 2, 21)
        sun.append((255, clamp(120 + n * 60), clamp(30 + n * 40), 255))
png(os.path.join('texturepacks', 'Vivid', 'assets', 'minecraft',
                 'textures', 'environment', 'sun.png'), S, S, sun)

MW, MH = 128, 64
moon = [(0, 0, 0, 0)] * (MW * MH)
for phase in range(8):
    cx, cy = (phase % 4) * S, (phase // 4) * S
    frac = phase / 8.0
    k = math.cos(frac * 2 * math.pi)
    for y in range(S):
        for x in range(S):
            nx = (x + 0.5) / S * 2 - 1
            ny = (y + 0.5) / S * 2 - 1
            term = k * math.sqrt(max(0.0, 1.0 - ny * ny))
            px = nx if frac <= 0.5 else -nx
            n = noise(x // 2, y // 2, 22)
            if px > term:
                col = (clamp(190 + n * 50), clamp(150 + n * 40), 255, 255)
            else:
                col = (40, 34, 70, 180)
            moon[(cy + y) * MW + (cx + x)] = col
png(os.path.join('texturepacks', 'Vivid', 'assets', 'minecraft',
                 'textures', 'environment', 'moon_phases.png'), MW, MH, moon)

with open(os.path.join('texturepacks', 'Vivid', 'pack.mcmeta'), 'w') as f:
    f.write('{\n  "pack": {\n    "pack_format": 15,\n'
            '    "description": "Vivid - sample pack for Delve"\n  }\n}\n')

print('wrote texturepacks/Vivid (%d block textures)' % len(blocks))
