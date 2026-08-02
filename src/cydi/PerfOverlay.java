package cydi;

import cydi.GpuProfiler.Zone;
import cydi.ShaderProfiler.Probe;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen performance readout.
 *
 * Three levels, cycled with F6: off, a compact frame-timing summary, and the
 * full breakdown including per-shader GPU times and the ablation-derived cost of
 * individual cloud functions. Selecting the full level runs the ablation
 * harness through one sweep of every probe while the diagnostics menu is open --
 * visibly flickering the clouds for that sweep, since the harness works by
 * actually disabling each function in the frame being drawn -- and then holds
 * the results on screen without further flicker until the overlay is cycled
 * off and back on.
 */
public final class PerfOverlay {

    public static final int OFF = 0;
    public static final int SUMMARY = 1;
    public static final int FULL = 2;

    private static final int FLOATS_PER_QUAD = 6 * 4;
    /** Width of the label column, in characters, for the aligned rows. */
    private static final int LABEL_WIDTH = 24;

    private static float[] text = new float[16384];
    private static float[] quads = new float[FLOATS_PER_QUAD * 64];
    private static final List<String> lines = new ArrayList<>();
    private static final List<float[]> barSpecs = new ArrayList<>();

    private PerfOverlay() {
    }

    /** Advances the F6 cycle, enabling the machinery each level needs. */
    public static void cycle() {
        int next = (Game.OPT_PERF_OVERLAY + 1) % 3;
        Game.OPT_PERF_OVERLAY = next;
        GpuProfiler.setEnabled(next != OFF);
        ShaderProfiler.setRunning(next == FULL);
        if (next == OFF) {
            Game.consoleMsg("Performance overlay off");
        } else if (next == SUMMARY) {
            Game.consoleMsg("Performance overlay: frame timings");
        } else {
            ShaderProfiler.reset();
            Game.consoleMsg("Performance overlay: shader profiling (brief flicker while it sweeps, then holds)");
        }
    }

    public static void render() {
        if (Game.OPT_PERF_OVERLAY == OFF || Game.WINDOW == null) {
            return;
        }
        float w = Game.WINDOW.getWidth();
        float h = Game.WINDOW.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        buildLines();

        float scale = Math.max(0.20f, Math.min(0.34f, h / 1080f * 0.30f));
        float lineH = TextRenderer.lineHeight(scale);
        float padding = 10f;
        float originX = 12f;
        float originY = 12f;

        float widest = 0f;
        for (String line : lines) {
            widest = Math.max(widest, TextRenderer.width(line, scale));
        }
        float panelW = widest + padding * 2f;
        float panelH = lines.size() * lineH + padding * 2f;

        ensureQuads(barSpecs.size() + 2);

        int v = 0;
        v += quad(quads, v, originX, originY, panelW, panelH, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0.04f, 0.05f, 0.07f, 0.82f);

        drawBars(originX, originY + padding, lineH, panelW, padding, w, h);

        ensureText(lines);
        int ti = 0;
        int totalVerts = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            int written = TextRenderer.emit(text, ti, line,
                    originX + padding, originY + padding + i * lineH, scale, w, h);
            ti += written * 4;
            totalVerts += written;
        }
        Renderer.drawHudQuads(text, totalVerts, TextRenderer.getTextureId(),
                0.92f, 0.95f, 1.0f, 1.0f);
    }

    /**
     * Draws the share bars behind the per-pass rows. Each spec is
     * {lineIndex, fraction, r, g, b}.
     */
    private static void drawBars(float originX, float textTop, float lineH,
                                 float panelW, float padding, float w, float h) {
        for (float[] spec : barSpecs) {
            float fraction = Math.max(0f, Math.min(1f, spec[1]));
            if (fraction <= 0.001f) {
                continue;
            }
            float barW = (panelW - padding * 2f) * fraction;
            float top = textTop + spec[0] * lineH + lineH * 0.12f;
            int v = quad(quads, 0, originX + padding, top, barW, lineH * 0.76f, w, h);
            Renderer.drawHudQuads(quads, v, 0, spec[2], spec[3], spec[4], 0.30f);
        }
    }

    private static void buildLines() {
        lines.clear();
        barSpecs.clear();

        double cpuFrame = FrameStats.frameMs();
        double gpuFrame = GpuProfiler.frameMs();

        lines.add("PERFORMANCE                        [F6]");
        lines.add(row("FPS", String.format("%6.1f", FrameStats.fps())));
        lines.add(row("frame (cpu wall)", ms(cpuFrame)));
        lines.add(row("  p99 / max", String.format("%6.2f /%6.2f ms",
                FrameStats.percentile(0.99), FrameStats.maxMs())));
        lines.add(row("  update (logic)", ms(FrameStats.updateMs())));
        lines.add(row("  submit (draw calls)", ms(FrameStats.renderMs())));
        lines.add(row("frame (gpu)", ms(gpuFrame)));
        if (Game.OPT_VSYNC) {
            // Under vsync the driver blocks inside draw submission, so the CPU
            // figures above are the refresh interval, not real work.
            lines.add("  vsync on: cpu times are capped [F8]");
        }

        if (!GpuProfiler.isSupported()) {
            lines.add("");
            lines.add("GPU timer queries unavailable.");
            return;
        }

        lines.add("");
        lines.add("SHADER PASSES              gpu ms   %");

        double total = Math.max(gpuFrame, 0.0001);
        for (Zone zone : Zone.ALL) {
            double zms = GpuProfiler.ms(zone);
            double share = zms / total;
            barSpecs.add(new float[]{lines.size(), (float) share, 0.35f, 0.62f, 0.95f});
            lines.add(String.format("%-14s %-16s %6.2f %3.0f%%",
                    zone.label, zone.shader, zms, share * 100.0));
        }
        double untimed = Math.max(0.0, gpuFrame - GpuProfiler.accountedMs());
        lines.add(String.format("%-14s %-16s %6.2f %3.0f%%",
                "(untimed)", "clear/blit/swap", untimed, untimed / total * 100.0));

        if (Game.OPT_PERF_OVERLAY == FULL) {
            appendAblation();
        }
        appendSettings();
        appendSky();
    }

    private static void appendAblation() {
        lines.add("");
        lines.add("FUNCTION COST BY ABLATION   " + ShaderProfiler.status());
        lines.add("  saving when switched off; overlaps, so");
        lines.add("  these sum to more than the pass total");
        if (ShaderProfiler.completedCycles() == 0) {
            lines.add("  measuring, first pass takes ~6s ...");
        }
        for (Probe probe : Probe.ALL) {
            if (!ShaderProfiler.hasResult(probe)) {
                lines.add(String.format("  %-26s     --", probe.label));
                continue;
            }
            double cost = ShaderProfiler.costMs(probe);
            lines.add(String.format("  %-26s %6.2f ms", probe.label, cost));
        }
    }

    private static void appendSettings() {
        lines.add("");
        lines.add("SETTINGS");
        int div = Math.max(1, Game.OPT_SKY_RESOLUTION_DIV);
        int w = Game.WINDOW.getWidth();
        int h = Game.WINDOW.getHeight();
        lines.add(row("resolution", w + "x" + h));
        lines.add(row("cloud resolution", (w / div) + "x" + (h / div) + "  (1/" + div + ")"));
        lines.add(row("cloud quality", Game.QUALITY_LABELS[Game.OPT_CLOUD_QUALITY]));
        lines.add(row("cloud opacity", String.format("%.0f%%", Game.OPT_CLOUD_OPACITY_SCALE * 100f)));
        lines.add(row("cloud shadow", String.format("%.0f%%", Game.OPT_CLOUD_SHADOW_SCALE * 100f)));
        lines.add(row("cloud march steps", String.valueOf(Game.OPT_CLOUD_VOL_STEPS)));
        lines.add(row("god rays", Game.QUALITY_LABELS[Game.OPT_GOD_RAYS_QUALITY]));
        lines.add(row("god rays offscreen", Game.OFFSCREEN_QUALITY_LABELS[Game.OPT_GOD_RAYS_OFFSCREEN_QUALITY]));
        lines.add(row("draw distance", String.valueOf(Game.OPT_DRAW_DISTANCE)));
        lines.add(row("vsync / AA", onOff(Game.OPT_VSYNC) + " / " + onOff(Game.OPT_ANTIALIASING)));
    }

    private static void appendSky() {
        lines.add("");
        lines.add("SKY STATE");
        lines.add(row("time of day", clockString() + "  (day " + Game.DAY_COUNT + ")"));
        lines.add(row("sun elevation", String.format("%+6.1f deg",
                Math.toDegrees(Math.asin(clamp(Renderer.getSunElevation(), -1f, 1f))))));
        lines.add(row("condition", Weather.condition.label));
        lines.add(row("coverage threshold", String.format("%5.3f  (low = more cloud)",
                Weather.cloudCoverage)));
        lines.add(row("wind", String.format("%4.2fx @ %5.1f deg",
                Weather.windSpeed, Math.toDegrees(Weather.windAngle) % 360.0)));
        lines.add(row("turbulence", String.format("%5.3f", Weather.turbulence)));
    }

    /** Formats the game clock as HH:MM, with 0.0 being midnight. */
    static String clockString() {
        float t = Game.TIME_OF_DAY % 1.0f;
        int minutes = (int) (t * 24f * 60f);
        return String.format("%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String row(String label, String value) {
        return String.format("%-" + LABEL_WIDTH + "s %s", label, value);
    }

    private static String ms(double value) {
        return String.format("%6.2f ms", value);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static void ensureText(List<String> content) {
        int chars = 0;
        for (String line : content) {
            chars += line.length();
        }
        int need = TextRenderer.floatsFor(chars);
        if (text.length < need) {
            text = new float[need];
        }
    }

    private static void ensureQuads(int count) {
        int need = count * FLOATS_PER_QUAD;
        if (quads.length < need) {
            quads = new float[need];
        }
    }

    private static int quad(float[] out, int offset, float px, float py,
                            float pw, float ph, float screenW, float screenH) {
        float x0 = (px / screenW) * 2f - 1f;
        float x1 = ((px + pw) / screenW) * 2f - 1f;
        float y0 = 1f - (py / screenH) * 2f;
        float y1 = 1f - ((py + ph) / screenH) * 2f;

        int i = offset * 4;
        i = put(out, i, x0, y0);
        i = put(out, i, x0, y1);
        i = put(out, i, x1, y1);
        i = put(out, i, x1, y1);
        i = put(out, i, x1, y0);
        put(out, i, x0, y0);
        return 6;
    }

    private static int put(float[] out, int i, float x, float y) {
        out[i] = x;
        out[i + 1] = y;
        out[i + 2] = 0f;
        out[i + 3] = 0f;
        return i + 4;
    }
}
