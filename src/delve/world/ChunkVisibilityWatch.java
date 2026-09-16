package delve.world;

import delve.core.Game;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Diagnostic tap for horizon flicker reports: watches every chunk's
 * per-frame draw-decision OUTCOME (drawn, concealment-held, frustum-culled,
 * not-ready) plus destroy/cancel/upload pulses, and prints a compact
 * [flash-watch] line whenever the population actually changed -- silence
 * means no flashes, lines name the exact transition (e.g. drawn>concealed)
 * in bulk with sample coordinates. Open-addressed long-to-byte table so
 * per-frame observation allocates nothing.
 */
final class ChunkVisibilityWatch {

    /** Zero-cost by default; run with -Ddelve.watch=true to re-enable. */
    static final boolean ENABLED = "true".equals(System.getProperty("delve.watch"));

    static final byte FORGET = -1, UNK = 0, M_NOTREADY = 1, C_HELD = 2, F_CULLED = 3, D_DRAWN = 4;

    private static final int CAP = 1 << 14;
    private static final long[] KEYS = new long[CAP];
    private static final byte[] VALS = new byte[CAP];

    private static final int[] TRANS = new int[5 * 5];
    private static final String[] SAMPLES = new String[6];
    private static int sampleCount;
    private static final AtomicIntegerArray PULSE = new AtomicIntegerArray('Z' + 1);
    private static float dipMin = 1.0f, dipMax = 1.0f;
    private static int dipEvents;
    /** Bearing histogram of flashes: OUT/IN per 16-octant, three distance bins. */
    private static final int[][] OUT_BY_BEARING = new int[16][3];
    private static final int[][] IN_BY_BEARING = new int[16][3];
    /** Dip attribution: how deep edgeFade vs lifecycle alpha sank. */
    private static float edgeDipMin = 1.0f, lifeDipMin = 1.0f;
    private static long nextFlushAtNanos;

    private ChunkVisibilityWatch() {
    }

    private static long slotKey(int i, int j) {
        return (((long) i + 262144L) << 20) | ((long) j + 262144L);
    }

    private static int probe(long key) {
        int idx = (int) (key & (CAP - 1));
        while (KEYS[idx] != 0 && KEYS[idx] != key) {
            idx = (idx + 1) & (CAP - 1);
        }
        return idx;
    }

    static void observe(WorldChunk ch, int i, int j, byte state) {
        observe(ch, i, j, state, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /** Player-relative overload: bearing/bin flashes when a transition fires. */
    static void observe(WorldChunk ch, int i, int j, byte state, int pcx, int pcy) {
        if (!ENABLED) {
            return;
        }
        long key = slotKey(i, j);
        int idx = probe(key);
        byte prev = KEYS[idx] == 0 ? UNK : VALS[idx];
        if (state == FORGET) {
            KEYS[idx] = 0;
            VALS[idx] = UNK;
            return;
        }
        if (KEYS[idx] == 0) {
            KEYS[idx] = key;
        }
        if (prev == UNK || VALS[idx] == state) {
            if (VALS[idx] == state && state == D_DRAWN && ch != null) {
                float a = ch.renderAlpha;
                if (a < dipMin) {
                    dipMin = a;
                }
                if (ch.visAlpha - a > 0.25f) {
                    dipEvents++;
                    dipMax = Math.max(dipMax, ch.visAlpha);
                    // Attribute the sink to its multiplicand.
                    float life = ch.lifecycleFadeAlpha();
                    float edge = life > 0.08f ? Math.min(1.0f, a / life) : 1.0f;
                    if (edge < edgeDipMin) {
                        edgeDipMin = edge;
                    }
                    if (life < lifeDipMin) {
                        lifeDipMin = life;
                    }
                }
                ch.visAlpha = a;
            }
            VALS[idx] = state;
            return;
        }
        VALS[idx] = state;
        TRANS[prev * 5 + state]++;
        if (pcx != Integer.MIN_VALUE) {
            int di = i - pcx, dj = j - pcy;
            int cheb = Math.max(Math.abs(di), Math.abs(dj));
            int bin = cheb <= 4 ? 0 : cheb <= 10 ? 1 : 2;
            int oct = (int) Math.floor(Math.atan2(dj, di) * (16.0 / (2 * Math.PI)) + 16) % 16;
            if (prev == D_DRAWN) {
                OUT_BY_BEARING[oct][bin]++;
            } else if (state == D_DRAWN) {
                IN_BY_BEARING[oct][bin]++;
            }
        }
        if (sampleCount < SAMPLES.length) {
            SAMPLES[sampleCount++] = stateName(prev) + ">" + stateName(state)
                    + "(" + i + "," + j + ")" + (ch == null ? "?" : "a" + (int) (ch.renderAlpha * 100));
        }
    }

    static void pulse(char tag) {
        if (ENABLED) {
            PULSE.incrementAndGet(tag);
        }
    }

    /** Called each frame; prints at most one line per second when nonzero. */
    static void flushThrottled(long nowNanos) {
        if (!ENABLED) {
            return;
        }
        if (nowNanos < nextFlushAtNanos) {
            return;
        }
        nextFlushAtNanos = nowNanos + 1_000_000_000L;
        StringBuilder sb = null;
        int total = 0;
        for (int p = 1; p <= 4; p++) {
            for (int c = 1; c <= 4; c++) {
                if (TRANS[p * 5 + c] == 0) {
                    continue;
                }
                if (sb == null) {
                    sb = new StringBuilder(96).append("[flash-watch] ");
                } else {
                    sb.append(' ');
                }
                sb.append(stateName((byte) p)).append('>').append(stateName((byte) c))
                        .append(':').append(TRANS[p * 5 + c]);
                total += TRANS[p * 5 + c];
                TRANS[p * 5 + c] = 0;
            }
        }
        int pulses = 0;
        StringBuilder ps = new StringBuilder();
        for (char t = 'A'; t <= 'Z'; t++) {
            int count = PULSE.getAndSet(t, 0);
            if (count > 0) {
                ps.append(t).append(':').append(count).append(' ');
                pulses += count;
            }
        }
        boolean dips = dipEvents > 0;
        boolean bearing = false;
        for (int[] row : OUT_BY_BEARING) {
            for (int v : row) {
                bearing |= v > 0;
            }
        }
        if (!bearing) {
            for (int[] row : IN_BY_BEARING) {
                for (int v : row) {
                    bearing |= v > 0;
                }
            }
        }
        if (sb == null && pulses == 0 && !dips && !bearing) {
            dipMin = 1.0f;
            dipEvents = 0;
            return;
        }
        if (sb == null) {
            sb = new StringBuilder(64).append("[flash-watch] ");
        }
        if (pulses > 0) {
            sb.append('|').append(ps.toString().trim());
        }
        if (dips) {
            sb.append("| dip ").append((int) (dipMin * 100)).append("..")
                    .append((int) (dipMax * 100)).append(" x").append(dipEvents)
                    .append(" E.").append((int) (edgeDipMin * 10))
                    .append(" L.").append((int) (lifeDipMin * 10));
            dipMin = 1.0f;
            dipMax = 1.0f;
            edgeDipMin = 1.0f;
            lifeDipMin = 1.0f;
            dipEvents = 0;
        }
        if (bearing) {
            String[] names = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};
            String[] bins = {"-", "midd", "far"};
            sb.append("| OUT");
            for (int o = 0; o < 16; o++) {
                for (int b = 1; b <= 2; b++) {
                    if (OUT_BY_BEARING[o][b] > 0) {
                        sb.append(names[o >> 1]).append('/').append(OUT_BY_BEARING[o][b])
                                .append('@').append(bins[b]).append(' ');
                    }
                    OUT_BY_BEARING[o][b] = 0;
                }
            }
            sb.append("| IN");
            for (int o = 0; o < 16; o++) {
                for (int b = 1; b <= 2; b++) {
                    if (IN_BY_BEARING[o][b] > 0) {
                        sb.append(names[o >> 1]).append('/').append(IN_BY_BEARING[o][b])
                                .append('@').append(bins[b]).append(' ');
                    }
                    IN_BY_BEARING[o][b] = 0;
                }
            }
        }
        if (sampleCount > 0) {
            sb.append(" | ").append(SAMPLES[0]);
            for (int k = 1; k < sampleCount; k++) {
                sb.append(',').append(SAMPLES[k]);
            }
            sampleCount = 0;
        }
        float yaw = Game.GAME_CAMERA == null ? 0f : Game.GAME_CAMERA.getYaw();
        sb.append(" | yaw").append((int) (((yaw % 360f) + 360f) % 360f))
                .append(" r").append(World.effectiveRenderDistance()).append(" n").append(total)
                .append(" fps").append(Game.FRAMES_PER_SECOND)
                .append(" b").append(World.builtSubmittedThisFrame()).append("/").append(World.MAX_CHUNKS_TO_BUILD)
                .append(" g").append(World.genSubmittedThisFrame());
        appendBlockers(sb);
        System.out.println(sb);
    }

    /** Name the stragglers capping the frontier ring by ring. */
    private static void appendBlockers(StringBuilder sb) {
        if (Game.GAME_CAMERA == null) {
            return;
        }
        int pcx = (int) Math.floor(Game.GAME_CAMERA.position.x / WorldChunk.sizeX);
        int pcz = (int) Math.floor(Game.GAME_CAMERA.position.z / WorldChunk.sizeZ);
        int from = Math.max(0, World.lastReadyFrontier + 1);
        int to = Math.min(World.effectiveRenderDistance(), from + 6);
        int count = 0;
        StringBuilder names = new StringBuilder(64);
        for (int r = from; r <= to; r++) {
            int ring = 0;
            for (int i = pcx - r; i <= pcx + r; i++) {
                for (int j = pcz - r; j <= pcz + r; j++) {
                    if (i != pcx - r && i != pcx + r && j != pcz - r && j != pcz + r) {
                        continue;
                    }
                    WorldChunk wc = World.getChunk(i, j);
                    char flag = ' ';
                    if (wc == null) {
                        flag = 'N';
                    } else if (wc.isGenerating) {
                        flag = 'g';
                    } else if (wc.vboVertexHandle == 0) {
                        if (wc.isBuilding) {
                            flag = 'B';
                        } else if (wc.hasPendingMesh()) {
                            flag = 'P';
                        } else if (!wc.isBuilt) {
                            flag = 'u';
                        }
                        // else: built EMPTY chunk (underground/solid) -- a
                        // VBO was never owed; genuinely ready, not a blocker.
                    }
                    if (flag == ' ') {
                        continue;
                    }
                    count++;
                    ring++;
                    if (names.length() < 96) {
                        names.append('(').append(i).append(',').append(j).append(')').append(flag)
                                .append('@').append(r).append(' ');
                    }
                }
            }
        }
        if (count > 0) {
            sb.append(" | blk:").append(count).append(" ").append(names.toString().trim());
        }
    }

    private static String stateName(byte s) {
        return switch (s) {
            case M_NOTREADY -> "M";
            case C_HELD -> "C";
            case F_CULLED -> "F";
            case D_DRAWN -> "D";
            default -> "?";
        };
    }
}
