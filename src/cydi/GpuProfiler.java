package cydi;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;
import static org.lwjgl.opengl.GL33.glQueryCounter;

/**
 * Per-pass GPU timing built on timestamp queries.
 *
 * Every pass writes a start and an end timestamp into the command stream, and
 * the difference is the time the GPU spent between them. Results are collected
 * {@link #RING} frames later: asking for the current frame's result would block
 * the CPU until the GPU drained, which both destroys the frame rate being
 * measured and changes the number it reports.
 *
 * Timestamps record when the GPU *reached* a point, not how much work it did, so
 * a pass that sits waiting on a starved CPU still looks expensive. The overlay
 * shows CPU frame time next to the GPU total so that case is recognisable.
 */
public final class GpuProfiler {

    /**
     * One timed span. Zones map one-to-one onto passes, and each pass is a
     * single shader program, so these double as per-shader timings.
     */
    public enum Zone {
        SKY      ("Sky + clouds", "skygradient.frag"),
        CELESTIAL("Sun / moon",   "sky.frag"),
        TERRAIN  ("Terrain",      "chunk.frag"),
        WATER    ("Water",        "chunk.frag"),
        GODRAYS  ("God rays",     "godrays.frag"),
        COMPOSITE("Composite",    "composite.frag"),
        HUD      ("HUD + text",   "hud.frag");

        public final String label;
        public final String shader;

        Zone(String label, String shader) {
            this.label = label;
            this.shader = shader;
        }

        public static final Zone[] ALL = values();
    }

    /**
     * Frames of latency before results are collected. Four is comfortably more
     * than any driver buffers, so availability checks effectively never fail and
     * the collection never blocks.
     */
    private static final int RING = 4;

    private static final int ZONES = Zone.ALL.length;
    /** Marker 0 is the frame start, 1 the frame end, then a pair per zone. */
    private static final int FRAME_START = 0;
    private static final int FRAME_END = 1;
    private static final int MARKERS = 2 + ZONES * 2;

    /** Weight of a new sample in the displayed running average. */
    private static final double SMOOTHING = 0.10;

    private static int[][] queries;
    private static boolean[][] written;
    private static boolean supported;
    private static boolean enabled;
    private static boolean frameOpen;
    private static long frameIndex;

    private static final double[] smoothed = new double[ZONES];
    private static final double[] latest = new double[ZONES];
    private static double smoothedFrame;
    private static double latestFrame;

    /** Totals since the last {@link #resetAccumulators()}, for periodic logging. */
    private static final double[] accum = new double[ZONES];
    private static double accumFrame;
    private static int accumSamples;

    private GpuProfiler() {
    }

    public static void init() {
        supported = true;
        queries = new int[RING][MARKERS];
        written = new boolean[RING][MARKERS];
        try {
            for (int slot = 0; slot < RING; slot++) {
                for (int m = 0; m < MARKERS; m++) {
                    queries[slot][m] = glGenQueries();
                }
            }
        } catch (Throwable t) {
            supported = false;
            System.out.println("GPU profiler unavailable: " + t);
        }
    }

    public static boolean isSupported() {
        return supported;
    }

    public static boolean isEnabled() {
        return enabled && supported;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        if (!enabled) {
            java.util.Arrays.fill(smoothed, 0.0);
            java.util.Arrays.fill(latest, 0.0);
            smoothedFrame = 0.0;
            latestFrame = 0.0;
            resetAccumulators();
        }
    }

    private static int slot() {
        return (int) (frameIndex % RING);
    }

    /** Opens a frame, first collecting the results of the frame being recycled. */
    public static void beginFrame() {
        if (!isEnabled()) {
            frameOpen = false;
            return;
        }
        int s = slot();
        if (frameIndex >= RING) {
            collect(s);
        }
        java.util.Arrays.fill(written[s], false);
        frameOpen = true;
        mark(s, FRAME_START);
    }

    public static void endFrame() {
        if (!frameOpen) {
            return;
        }
        mark(slot(), FRAME_END);
        frameOpen = false;
        frameIndex++;
    }

    public static void begin(Zone zone) {
        if (!frameOpen) {
            return;
        }
        mark(slot(), 2 + zone.ordinal() * 2);
    }

    public static void end(Zone zone) {
        if (!frameOpen) {
            return;
        }
        mark(slot(), 3 + zone.ordinal() * 2);
    }

    private static void mark(int s, int marker) {
        glQueryCounter(queries[s][marker], GL_TIMESTAMP);
        written[s][marker] = true;
    }

    /**
     * Reads back one frame's timestamps. A zone that did not run this frame has
     * no markers written, so its query object still holds a stale value from
     * whichever frame last used the slot; the written flags are what keep that
     * out of the results.
     */
    private static void collect(int s) {
        boolean[] w = written[s];
        if (!w[FRAME_START] || !w[FRAME_END]) {
            return;
        }
        if (glGetQueryObjecti(queries[s][FRAME_END], GL_QUERY_RESULT_AVAILABLE) == 0) {
            return;
        }

        long frameStart = glGetQueryObjecti64(queries[s][FRAME_START], GL_QUERY_RESULT);
        long frameEnd = glGetQueryObjecti64(queries[s][FRAME_END], GL_QUERY_RESULT);
        latestFrame = (frameEnd - frameStart) / 1.0e6;
        smoothedFrame += (latestFrame - smoothedFrame) * SMOOTHING;

        for (int z = 0; z < ZONES; z++) {
            int b = 2 + z * 2;
            int e = 3 + z * 2;
            double ms = 0.0;
            if (w[b] && w[e]) {
                long t0 = glGetQueryObjecti64(queries[s][b], GL_QUERY_RESULT);
                long t1 = glGetQueryObjecti64(queries[s][e], GL_QUERY_RESULT);
                ms = (t1 - t0) / 1.0e6;
            }
            latest[z] = ms;
            smoothed[z] += (ms - smoothed[z]) * SMOOTHING;
            accum[z] += ms;
        }

        accumFrame += latestFrame;
        accumSamples++;
    }

    /** Smoothed GPU time for a zone, in milliseconds. */
    public static double ms(Zone zone) {
        return smoothed[zone.ordinal()];
    }

    /** Most recent single-frame GPU time for a zone, in milliseconds. */
    public static double lastMs(Zone zone) {
        return latest[zone.ordinal()];
    }

    /** Smoothed total GPU time for the frame, in milliseconds. */
    public static double frameMs() {
        return smoothedFrame;
    }

    public static double lastFrameMs() {
        return latestFrame;
    }

    /** Sum of the smoothed zone times, which excludes any untimed work. */
    public static double accountedMs() {
        double total = 0.0;
        for (int z = 0; z < ZONES; z++) {
            total += smoothed[z];
        }
        return total;
    }

    /** Mean GPU time for a zone across the current logging window. */
    public static double meanMs(Zone zone) {
        return accumSamples == 0 ? 0.0 : accum[zone.ordinal()] / accumSamples;
    }

    public static double meanFrameMs() {
        return accumSamples == 0 ? 0.0 : accumFrame / accumSamples;
    }

    public static int sampleCount() {
        return accumSamples;
    }

    public static void resetAccumulators() {
        java.util.Arrays.fill(accum, 0.0);
        accumFrame = 0.0;
        accumSamples = 0;
    }

    public static void cleanup() {
        if (queries == null) {
            return;
        }
        for (int slot = 0; slot < RING; slot++) {
            for (int m = 0; m < MARKERS; m++) {
                if (queries[slot][m] != 0) {
                    glDeleteQueries(queries[slot][m]);
                }
            }
        }
        queries = null;
    }
}
