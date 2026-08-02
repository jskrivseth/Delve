package cydi;

import cydi.GpuProfiler.Zone;

/**
 * Per-function cost attribution for the cloud shaders.
 *
 * GLSL cannot time itself: there is no clock in the language, no way to return
 * anything but a pixel, and timestamp queries only bracket whole draw calls. The
 * cost of an individual function is therefore measured by ablation -- the
 * function is switched off through a uniform for a few frames and the drop in
 * the pass's GPU time is attributed to it.
 *
 * Two details make the numbers trustworthy. A baseline slot is measured either
 * side of every probe, because the camera keeps moving and an unpaired baseline
 * would fold that drift into the estimate. And the switch is a uniform rather
 * than a preprocessor flag, so the driver cannot dead-code the branch away and
 * hand back a saving that would never occur in the shipped shader.
 *
 * Because the switch is a uniform the real frame renders with, the ablated
 * function really is missing from what's on screen while it is probed -- there
 * is no way to measure a function's cost without briefly not paying it. Rather
 * than cycle through the probes forever (which flickers the visible clouds for
 * as long as the overlay stays open), the harness runs exactly one sweep after
 * being enabled and then holds: once every probe has a result, it stops
 * advancing and `activeMask()` reports zero, so the last full-detail image is
 * what stays on screen.
 *
 * The estimates remain approximations: disabling a function also changes the
 * density field, which changes how often the march terminates early. Treat them
 * as a ranking, not a budget.
 */
public final class ShaderProfiler {

    /** One ablatable function. Flags mirror the AB_* defines in clouds.glsl. */
    public enum Probe {
        COVERAGE(1,   "cloudCoverageAt  fbm x4",  Zone.SKY, Zone.TERRAIN),
        MASK_HI (2,   "cloudMaskAt  detail fbm3", Zone.SKY, Zone.TERRAIN),
        DRAPE   (4,   "cloudDrapeBase",           Zone.SKY, Zone.TERRAIN),
        LUMPS   (8,   "underside fbm3",           Zone.SKY, Zone.TERRAIN),
        BILLOW  (16,  "billow3  3D erosion",      Zone.SKY),
        LIGHT   (32,  "in-cloud light march",     Zone.SKY),
        SHADOWS (64,  "terrain cloud shadows",    Zone.TERRAIN),
        STEPS   (128, "half the march steps",     Zone.SKY);

        public final int flag;
        public final String label;
        private final Zone[] zones;

        Probe(int flag, String label, Zone... zones) {
            this.flag = flag;
            this.label = label;
            this.zones = zones;
        }

        /** Sums the zones this probe can plausibly affect. */
        double sum(double[] zoneMs) {
            double total = 0.0;
            for (Zone z : zones) {
                total += zoneMs[z.ordinal()];
            }
            return total;
        }

        public String zoneNames() {
            StringBuilder sb = new StringBuilder();
            for (Zone z : zones) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(z.label);
            }
            return sb.toString();
        }

        public static final Probe[] ALL = values();
    }

    private static final int ZONES = Zone.ALL.length;
    private static final int PROBES = Probe.ALL.length;
    /** Frames discarded after switching mask, letting the pipeline flush. */
    private static final int WARMUP_FRAMES = 6;
    /** Frames averaged per slot. */
    private static final int SAMPLE_FRAMES = 14;
    /** Baseline, probe, baseline, probe ... so every probe is bracketed. */
    private static final int SLOTS = PROBES * 2;

    private static boolean running;
    /** True once every probe has a result; activeMask() reports zero from then on. */
    private static boolean finished;
    private static int slot;
    private static int frameInSlot;

    private static final double[] slotSum = new double[ZONES];
    private static int slotSamples;

    private static final double[] baseBefore = new double[ZONES];
    private static final double[] baseAfter = new double[ZONES];
    private static final double[] probeMean = new double[ZONES];
    private static boolean haveBaseBefore;

    /** Smoothed cost estimate per probe, in milliseconds. */
    private static final double[] costMs = new double[PROBES];
    private static final boolean[] measured = new boolean[PROBES];
    private static int completedCycles;

    private ShaderProfiler() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setRunning(boolean value) {
        if (running == value) {
            return;
        }
        running = value;
        slot = 0;
        frameInSlot = 0;
        slotSamples = 0;
        haveBaseBefore = false;
        java.util.Arrays.fill(slotSum, 0.0);
        if (!running) {
            return;
        }
        finished = false;
        // Ablation needs per-pass timings to compare against.
        GpuProfiler.setEnabled(true);
    }

    public static void reset() {
        java.util.Arrays.fill(costMs, 0.0);
        java.util.Arrays.fill(measured, false);
        completedCycles = 0;
        finished = false;
        slot = 0;
        frameInSlot = 0;
        slotSamples = 0;
        haveBaseBefore = false;
    }

    /** True once one full sweep has measured every probe and the harness has stopped probing. */
    public static boolean isFinished() {
        return finished;
    }

    /** Bitmask to push to the shaders this frame. Zero when not probing. */
    public static int activeMask() {
        if (!running || finished) {
            return 0;
        }
        return isBaselineSlot() ? 0 : Probe.ALL[slot / 2].flag;
    }

    private static boolean isBaselineSlot() {
        return (slot % 2) == 0;
    }

    /**
     * Advances the state machine. Called once per frame after the GPU profiler
     * has collected results, so the timings read here belong to a frame that
     * already ran under the current mask.
     */
    public static void update() {
        if (!running || finished || Game.OPT_PERF_OVERLAY != PerfOverlay.FULL || !GpuProfiler.isEnabled()) {
            return;
        }
        frameInSlot++;
        if (frameInSlot <= WARMUP_FRAMES) {
            return;
        }

        for (int z = 0; z < ZONES; z++) {
            slotSum[z] += GpuProfiler.lastMs(Zone.ALL[z]);
        }
        slotSamples++;

        if (slotSamples < SAMPLE_FRAMES) {
            return;
        }

        double[] mean = new double[ZONES];
        for (int z = 0; z < ZONES; z++) {
            mean[z] = slotSum[z] / slotSamples;
        }
        finishSlot(mean);

        java.util.Arrays.fill(slotSum, 0.0);
        slotSamples = 0;
        frameInSlot = 0;
        slot = (slot + 1) % SLOTS;
        if (slot == 0) {
            completedCycles++;
        }
        if (allMeasured()) {
            // One sweep has a result for every probe -- stop here rather than
            // looping forever, so the visible clouds settle back to full
            // detail instead of continuing to flicker while the overlay stays
            // open.
            finished = true;
        }
    }

    private static boolean allMeasured() {
        for (boolean m : measured) {
            if (!m) {
                return false;
            }
        }
        return true;
    }

    private static void finishSlot(double[] mean) {
        if (isBaselineSlot()) {
            if (haveBaseBefore) {
                // This baseline closes the bracket around the previous probe.
                System.arraycopy(mean, 0, baseAfter, 0, ZONES);
                int previousProbe = ((slot - 1 + SLOTS) % SLOTS) / 2;
                record(previousProbe);
            }
            System.arraycopy(mean, 0, baseBefore, 0, ZONES);
            haveBaseBefore = true;
        } else {
            System.arraycopy(mean, 0, probeMean, 0, ZONES);
        }
    }

    private static void record(int probeIndex) {
        Probe probe = Probe.ALL[probeIndex];
        double base = (probe.sum(baseBefore) + probe.sum(baseAfter)) * 0.5;
        double ablatedCost = probe.sum(probeMean);
        double delta = base - ablatedCost;

        if (measured[probeIndex]) {
            costMs[probeIndex] += (delta - costMs[probeIndex]) * 0.35;
        } else {
            costMs[probeIndex] = delta;
            measured[probeIndex] = true;
        }
    }

    public static double costMs(Probe probe) {
        return costMs[probe.ordinal()];
    }

    public static boolean hasResult(Probe probe) {
        return measured[probe.ordinal()];
    }

    public static int completedCycles() {
        return completedCycles;
    }

    /** Human-readable description of what the harness is doing right now. */
    public static String status() {
        if (!running) {
            return "off";
        }
        if (finished) {
            return "done, holding";
        }
        if (isBaselineSlot()) {
            return "baseline " + (slot / 2 + 1) + "/" + PROBES;
        }
        return "ablate " + Probe.ALL[slot / 2].name();
    }
}
