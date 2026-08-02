package cydi;

/**
 * CPU-side frame timing.
 *
 * The GPU profiler answers "what is the graphics card doing", which is only half
 * the picture: a frame can be slow because the main thread spent it generating
 * chunks, or because it blocked in swapBuffers waiting on vsync. Keeping both
 * makes it obvious which side is actually the limit.
 */
public final class FrameStats {

    /** Frames retained for the distribution readout, about four seconds at 60Hz. */
    private static final int HISTORY = 256;

    private static final double[] frameMs = new double[HISTORY];
    private static int count;
    private static int writeIndex;

    private static long frameStartNs;
    private static long updateStartNs;
    private static long renderStartNs;

    private static double lastFrameMs;
    private static double lastUpdateMs;
    private static double lastRenderMs;

    private static double smoothedFrameMs;
    private static double smoothedUpdateMs;
    private static double smoothedRenderMs;

    private static final double SMOOTHING = 0.10;

    private FrameStats() {
    }

    public static void beginFrame() {
        frameStartNs = System.nanoTime();
    }

    public static void beginUpdate() {
        updateStartNs = System.nanoTime();
    }

    public static void endUpdate() {
        lastUpdateMs = (System.nanoTime() - updateStartNs) / 1.0e6;
        smoothedUpdateMs += (lastUpdateMs - smoothedUpdateMs) * SMOOTHING;
    }

    public static void beginRender() {
        renderStartNs = System.nanoTime();
    }

    public static void endRender() {
        lastRenderMs = (System.nanoTime() - renderStartNs) / 1.0e6;
        smoothedRenderMs += (lastRenderMs - smoothedRenderMs) * SMOOTHING;
    }

    /** Closes the frame. Called after the buffer swap so vsync wait is included. */
    public static void endFrame() {
        lastFrameMs = (System.nanoTime() - frameStartNs) / 1.0e6;
        smoothedFrameMs += (lastFrameMs - smoothedFrameMs) * SMOOTHING;

        frameMs[writeIndex] = lastFrameMs;
        writeIndex = (writeIndex + 1) % HISTORY;
        if (count < HISTORY) {
            count++;
        }
    }

    public static double frameMs() {
        return smoothedFrameMs;
    }

    /** Time spent in game logic, excluding rendering. */
    public static double updateMs() {
        return smoothedUpdateMs;
    }

    /** Time spent submitting draw calls, excluding the buffer swap. */
    public static double renderMs() {
        return smoothedRenderMs;
    }

    public static double lastFrameMs() {
        return lastFrameMs;
    }

    public static double fps() {
        return smoothedFrameMs > 0.0001 ? 1000.0 / smoothedFrameMs : 0.0;
    }

    /**
     * The frame time that only 1 frame in 100 exceeds. Averages hide stutter,
     * and stutter is what a player actually notices.
     */
    public static double percentile(double fraction) {
        if (count == 0) {
            return 0.0;
        }
        double[] sorted = new double[count];
        System.arraycopy(frameMs, 0, sorted, 0, count);
        java.util.Arrays.sort(sorted);
        int index = (int) Math.round(fraction * (count - 1));
        return sorted[Math.max(0, Math.min(count - 1, index))];
    }

    public static double maxMs() {
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, frameMs[i]);
        }
        return max;
    }

    public static int sampleCount() {
        return count;
    }
}
