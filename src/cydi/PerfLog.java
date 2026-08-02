package cydi;

import cydi.GpuProfiler.Zone;
import cydi.ShaderProfiler.Probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Periodic performance sampling to disk.
 *
 * A frame captured the moment something looks slow is rarely the frame worth
 * looking at, and reading numbers off an overlay while playing is unreliable.
 * This writes a snapshot every {@link #INTERVAL_MS} containing the settings, the
 * sky state and the per-pass GPU breakdown, so a session can be reviewed
 * afterwards and slow stretches correlated with what was on screen.
 *
 * Two files are produced next to the game: a readable log and a CSV suited to
 * plotting.
 */
public final class PerfLog {

    private static final long INTERVAL_MS = 10_000;
    private static final Path LOG_PATH = Paths.get("perf.log");
    private static final Path CSV_PATH = Paths.get("perf.csv");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static boolean enabled;
    private static long lastSampleMs;
    private static boolean headerWritten;
    private static int sampleIndex;

    // Previous settings, so a change can be marked in the log when it happens.
    private static int lastDiv;
    private static int lastCloudQuality;
    private static boolean lastClouds;
    private static boolean lastGodRays;
    private static boolean lastVsync;
    private static int lastDrawDistance;
    private static int lastWidth;
    private static int lastHeight;

    private PerfLog() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        if (enabled) {
            // Sampling is meaningless without per-pass timings to record.
            GpuProfiler.setEnabled(true);
            GpuProfiler.resetAccumulators();
            lastSampleMs = System.currentTimeMillis();
            // consoleMsg writes to Game.MESSAGES, which nothing currently renders,
            // so the only reliable confirmation is stdout.
            System.out.println("[perf] logging to " + LOG_PATH.toAbsolutePath()
                    + " and " + CSV_PATH.toAbsolutePath());
            Game.consoleMsg("Perf logging to " + LOG_PATH.toAbsolutePath());
        } else {
            System.out.println("[perf] logging stopped");
            Game.consoleMsg("Perf logging stopped");
        }
    }

    /** Called once per frame; writes a sample when the interval has elapsed. */
    public static void update() {
        if (!enabled || Game.SCREEN != Game.Screen.PLAYING) {
            return;
        }
        noteSettingsChange();

        long now = System.currentTimeMillis();
        if (now - lastSampleMs < INTERVAL_MS) {
            return;
        }
        lastSampleMs = now;
        if (GpuProfiler.sampleCount() < 5) {
            return;
        }
        writeSample();
        GpuProfiler.resetAccumulators();
    }

    /**
     * Records setting changes as they happen. A sample taken ten seconds later
     * would average across both configurations and read as noise, so the change
     * has to be marked at the moment it occurs.
     */
    private static void noteSettingsChange() {
        if (Game.WINDOW == null) {
            return;
        }
        int div = Math.max(1, Game.OPT_SKY_RESOLUTION_DIV);
        int w = Game.WINDOW.getWidth();
        int h = Game.WINDOW.getHeight();
        if (div == lastDiv && Game.OPT_CLOUD_QUALITY == lastCloudQuality
                && Game.OPT_CLOUDS == lastClouds && Game.OPT_GOD_RAYS == lastGodRays
                && Game.OPT_VSYNC == lastVsync && Game.OPT_DRAW_DISTANCE == lastDrawDistance
                && w == lastWidth && h == lastHeight) {
            return;
        }
        boolean first = lastWidth == 0;
        lastDiv = div;
        lastCloudQuality = Game.OPT_CLOUD_QUALITY;
        lastClouds = Game.OPT_CLOUDS;
        lastGodRays = Game.OPT_GOD_RAYS;
        lastVsync = Game.OPT_VSYNC;
        lastDrawDistance = Game.OPT_DRAW_DISTANCE;
        lastWidth = w;
        lastHeight = h;
        if (first) {
            return;
        }
        note(String.format("settings: %dx%d cloud 1/%d %s, clouds %s, rays %s,"
                        + " vsync %s, dist %d",
                w, h, div, Game.QUALITY_LABELS[Game.OPT_CLOUD_QUALITY].toLowerCase(),
                Game.OPT_CLOUDS ? "on" : "off", Game.OPT_GOD_RAYS ? "on" : "off",
                Game.OPT_VSYNC ? "on" : "off", Game.OPT_DRAW_DISTANCE));
        // Timings either side of the change must not be blended together.
        GpuProfiler.resetAccumulators();
        lastSampleMs = System.currentTimeMillis();
    }

    private static void writeSample() {
        sampleIndex++;
        try {
            writeReadable();
            writeCsv();
        } catch (IOException e) {
            System.err.println("Perf log write failed: " + e.getMessage());
            enabled = false;
        }
    }

    private static void writeReadable() throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== sample ").append(sampleIndex)
          .append("  ").append(LocalDateTime.now().format(STAMP))
          .append("  (").append(GpuProfiler.sampleCount()).append(" frames)\n");

        sb.append(String.format("  fps %.1f | cpu %.2f ms (update %.2f, submit %.2f)"
                        + " | p99 %.2f | gpu %.2f ms%n",
                FrameStats.fps(), FrameStats.frameMs(), FrameStats.updateMs(),
                FrameStats.renderMs(), FrameStats.percentile(0.99),
                GpuProfiler.meanFrameMs()));

        int div = Math.max(1, Game.OPT_SKY_RESOLUTION_DIV);
        sb.append(String.format("  %dx%d | cloud 1/%d %s | dist %d | rays %s | vsync %s | aa %s%n",
                Game.WINDOW.getWidth(), Game.WINDOW.getHeight(), div,
                Game.QUALITY_LABELS[Game.OPT_CLOUD_QUALITY].toLowerCase(),
                Game.OPT_DRAW_DISTANCE,
                Game.QUALITY_LABELS[Game.OPT_GOD_RAYS_QUALITY].toLowerCase(),
                Game.OPT_VSYNC ? "on" : "off",
                Game.OPT_ANTIALIASING ? "on" : "off"));
        sb.append(String.format("  clouds active: %s | godrays drawn: %s | gpu accounted %.2f ms%n",
                Renderer.areCloudsActive() ? "yes" : "no",
                Renderer.wereGodRaysDrawn() ? "yes" : "no",
                GpuProfiler.accountedMs()));

        sb.append(String.format("  %s day %d | sun %+.1f deg | %s | cover %.3f"
                        + " | wind %.2fx @ %.0f | turb %.3f%n",
                PerfOverlay.clockString(), Game.DAY_COUNT, sunElevationDegrees(),
                Weather.condition.label, Weather.cloudCoverage,
                Weather.windSpeed, Math.toDegrees(Weather.windAngle) % 360.0,
                Weather.turbulence));

        sb.append("  passes:");
        for (Zone zone : Zone.ALL) {
            sb.append(String.format(" %s=%.2f", zone.name().toLowerCase(), GpuProfiler.meanMs(zone)));
        }
        sb.append('\n');

        if (ShaderProfiler.isRunning()) {
            sb.append("  function cost:");
            for (Probe probe : Probe.ALL) {
                if (ShaderProfiler.hasResult(probe)) {
                    sb.append(String.format(" %s=%.2f", probe.name().toLowerCase(),
                            ShaderProfiler.costMs(probe)));
                }
            }
            sb.append('\n');
        }

        append(LOG_PATH, sb.toString());
    }

    private static void writeCsv() throws IOException {
        if (!headerWritten && !Files.exists(CSV_PATH)) {
            StringBuilder head = new StringBuilder(
                    "time,fps,cpu_ms,update_ms,submit_ms,p99_ms,gpu_ms,"
                    + "width,height,sky_div,cloud_quality,draw_dist,god_rays,vsync,"
                    + "clouds_active,godrays_drawn,gpu_accounted_ms,"
                    + "time_of_day,sun_deg,condition,coverage,wind_speed,turbulence");
            for (Zone zone : Zone.ALL) {
                head.append(',').append(zone.name().toLowerCase()).append("_ms");
            }
            append(CSV_PATH, head.append('\n').toString());
        }
        headerWritten = true;

        StringBuilder sb = new StringBuilder(256);
        sb.append(LocalDateTime.now().format(STAMP)).append(',')
          .append(String.format("%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,",
                  FrameStats.fps(), FrameStats.frameMs(), FrameStats.updateMs(),
                  FrameStats.renderMs(), FrameStats.percentile(0.99),
                  GpuProfiler.meanFrameMs()))
          .append(Game.WINDOW.getWidth()).append(',')
          .append(Game.WINDOW.getHeight()).append(',')
          .append(Math.max(1, Game.OPT_SKY_RESOLUTION_DIV)).append(',')
          .append(Game.QUALITY_LABELS[Game.OPT_CLOUD_QUALITY].toLowerCase()).append(',')
          .append(Game.OPT_DRAW_DISTANCE).append(',')
          .append(Game.QUALITY_LABELS[Game.OPT_GOD_RAYS_QUALITY].toLowerCase()).append(',')
          .append(Game.OPT_VSYNC).append(',')
          .append(Renderer.areCloudsActive()).append(',')
          .append(Renderer.wereGodRaysDrawn()).append(',')
          .append(String.format("%.3f,", GpuProfiler.accountedMs()))
          .append(PerfOverlay.clockString()).append(',')
          .append(String.format("%.2f,", sunElevationDegrees()))
          .append(Weather.condition.name()).append(',')
          .append(String.format("%.4f,%.3f,%.4f",
                  Weather.cloudCoverage, Weather.windSpeed, Weather.turbulence));

        for (Zone zone : Zone.ALL) {
            sb.append(',').append(String.format("%.3f", GpuProfiler.meanMs(zone)));
        }
        append(CSV_PATH, sb.append('\n').toString());
    }

    private static double sunElevationDegrees() {
        float s = Math.max(-1f, Math.min(1f, Renderer.getSunElevation()));
        return Math.toDegrees(Math.asin(s));
    }

    private static void append(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Writes a one-off marker, used when a setting changes mid-session. */
    public static void note(String message) {
        if (!enabled) {
            return;
        }
        try {
            append(LOG_PATH, "--- " + LocalDateTime.now().format(STAMP)
                    + "  " + message + "\n");
        } catch (IOException e) {
            System.err.println("Perf log write failed: " + e.getMessage());
        }
    }
}
