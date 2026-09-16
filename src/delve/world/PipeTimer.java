package delve.world;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapping stage-stopwatches for the chunk pipeline. Every pipeline stage
 * (generate, mesh, upload, pending-age, teardown-wait) measures itself with
 * begin/end tickets; the HUD rolls the totals once per second so a video of
 * the debug overlay isolates which stage hoards the latency: generation,
 * meshing, the pending-mesh queue, GL upload, or release.
 */
public final class PipeTimer {

    public static final int GEN = 0;      // world generation worker time
    public static final int MESH = 1;     // mesher worker time
    public static final int UPLD = 2;     // GL upload exec time
    public static final int PEND = 3;     // age of pending mesh (born -> uploaded)
    public static final int TEAR = 4;     // destroy-queue wait (condemned -> freed)
    public static final int STEADY = 5;   // update() render-loop pass length
    static final int SLOTS = 6;
    static final String[] NAMES = {"gen ", "mesh", "upld", "pend", "tear", "loop"};

    private static final AtomicLong[] NS = newSlots();
    private static final AtomicLong[] OPS = newSlots();
    private static final AtomicLong[] MX = newSlots();
    // Last rolled second (rates) for quick programmatic reads:
    public static volatile double lastRatePerSec[] = new double[SLOTS];
    public static volatile double lastAvgMs[] = new double[SLOTS];
    public static volatile double lastMaxMs[] = new double[SLOTS];
    private static long lastRollAtNanos = System.nanoTime();

    private static AtomicLong[] newSlots() {
        AtomicLong[] a = new AtomicLong[SLOTS];
        for (int i = 0; i < SLOTS; i++) a[i] = new AtomicLong();
        return a;
    }

    /** Ticket for begin/end timing. Ages (PEND/TEAR) pass start-nanos directly. */
    public static long begin() {
        return System.nanoTime();
    }

    public static void end(int slot, long ticketNanos) {
        long dur = System.nanoTime() - ticketNanos;
        if (dur < 0) return;
        NS[slot].addAndGet(dur);
        OPS[slot].incrementAndGet();
        AtomicLong mx = MX[slot];
        long prev;
        do { prev = mx.get(); if (prev >= dur) break; } while (!mx.compareAndSet(prev, dur));
    }

    /** Record an age (already-elapsed duration) rather than timed around code. */
    public static void age(int slot, long ageNanos) {
        if (ageNanos < 0) return;
        OPS[slot].incrementAndGet();
        NS[slot].addAndGet(ageNanos);
        AtomicLong mx = MX[slot];
        long prev;
        do { prev = mx.get(); if (prev >= ageNanos) return; } while (!mx.compareAndSet(prev, ageNanos));
    }

    /** Snapshot the rolling monitor interval into readable arrays; zeroes counters. */
    public static void roll() {
        long now = System.nanoTime();
        double elapsedSeconds = Math.max(0.001, (now - lastRollAtNanos) / 1_000_000_000.0);
        lastRollAtNanos = now;
        for (int s = 0; s < SLOTS; s++) {
            long ns = NS[s].getAndSet(0);
            long ops = OPS[s].getAndSet(0);
            long mx = MX[s].getAndSet(0);
            lastRatePerSec[s] = ops / elapsedSeconds;
            lastAvgMs[s] = ops == 0 ? 0 : (ns / (double) ops) / 1_000_000.0;
            lastMaxMs[s] = mx / 1_000_000.0;
        }
    }

    /** One HUD line: "<name>=<n>/s avg..<ms max..>" per slot with given names. */
    public static String line(int slot) {
        return NAMES[slot] + "=" + (int) lastRatePerSec[slot] + "/s "
                + fmt(lastAvgMs[slot]) + "/" + fmt(lastMaxMs[slot]) + "ms";
    }

    static String fmt(double ms) {
        if (ms >= 1000) return String.format("%.1fs", ms / 1000.0);
        if (ms >= 10) return String.format("%.0fms", ms);
        return String.format("%.2fms", ms);
    }

    private PipeTimer() {}
}
