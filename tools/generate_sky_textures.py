import io, math, struct, zlib, os

def png(path, w, h, px):
    """px: list of (r,g,b,a) rows-major."""
    raw = b''
    for y in range(h):
        raw += b'\x00'
        for x in range(w):
            r, g, b, a = px[y * w + x]
            raw += struct.pack('BBBB', r, g, b, a)
    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)
    out = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
    out += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(out)

def hash_noise(x, y, salt):
    h = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    h &= 0xFFFFFFFF
    h ^= (h >> 13); h = (h * 1274126177) & 0xFFFFFFFF; h ^= (h >> 16)
    return (h & 0xFF) / 255.0

OUT = os.path.join('media', 'art')
os.makedirs(OUT, exist_ok=True)

# --- sun.png : 32x32, classic Minecraft size -------------------------------
S = 32
sun = []
for y in range(S):
    for x in range(S):
        # Chunky 2x2 pixel blocks keep it reading as blocky pixel art.
        bx, by = x // 2, y // 2
        n = hash_noise(bx, by, 1)
        r = 255
        g = 226 + int(n * 26) - 13
        b = 120 + int(n * 60) - 30
        sun.append((r, max(0, min(255, g)), max(0, min(255, b)), 255))
png(os.path.join(OUT, 'sun.png'), S, S, sun)

# --- moon_phases.png : 128x64, modern 4x2 grid of 32x32 phases -------------
MW, MH, T = 128, 64, 32
moon = [(0, 0, 0, 0)] * (MW * MH)
for phase in range(8):
    cx = (phase % 4) * T
    cy = (phase // 4) * T
    # Fraction of the disc lit, sweeping across the tile.
    frac = phase / 8.0
    k = math.cos(frac * 2 * math.pi)
    for y in range(T):
        for x in range(T):
            nx = (x + 0.5) / T * 2 - 1
            ny = (y + 0.5) / T * 2 - 1
            bx, by = x // 2, y // 2
            n = hash_noise(bx, by, 7)

            # Terminator: a semi-ellipse whose width tracks the phase.
            term = k * math.sqrt(max(0.0, 1.0 - ny * ny))
            px = nx if frac <= 0.5 else -nx
            lit = px > term

            base = 214 + int(n * 40) - 20
            if lit:
                r = min(255, base + 16); g = min(255, base + 18); b = 255
                a = 255
            else:
                # Dark limb kept faintly visible as earthshine.
                r = int(base * 0.16); g = int(base * 0.17); b = int(base * 0.26)
                a = 190
            moon[(cy + y) * MW + (cx + x)] = (r, g, b, a)
png(os.path.join(OUT, 'moon_phases.png'), MW, MH, moon)

print('wrote media/art/sun.png (32x32) and media/art/moon_phases.png (128x64)')
