/*
 * In-game settings menu.
 */
package cydi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Escape-toggled settings overlay.
 *
 * Rows are declared as a label plus a value supplier and an activate action, so
 * adding a setting needs no changes to layout or hit testing.
 */
public class Menu {

    private static final class Row {
        final String label;
        final Supplier<String> value;
        final Runnable onActivate;
        final Runnable onRight;

        Row(String label, Supplier<String> value, Runnable onActivate, Runnable onRight) {
            this.label = label;
            this.value = value;
            this.onActivate = onActivate;
            this.onRight = onRight;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final MenuPanel panel = new MenuPanel();
    private final List<String> labels = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private final Game game;
    private int hovered = -1;

    public Menu(Game game) {
        this.game = game;
        build();
    }

    private void build() {
        rows.add(new Row("Ambient Occlusion",
                () -> Game.OPT_AMBIENT_OCCLUSION ? "On" : "Off",
                () -> Game.OPT_AMBIENT_OCCLUSION = !Game.OPT_AMBIENT_OCCLUSION, null));

        rows.add(new Row("Antialiasing",
                () -> Game.OPT_ANTIALIASING ? "On" : "Off",
                () -> {
                    Game.OPT_ANTIALIASING = !Game.OPT_ANTIALIASING;
                    Renderer.setAntialiasing(Game.OPT_ANTIALIASING);
                }, null));

        rows.add(new Row("Render Distance",
                () -> String.valueOf(Game.OPT_DRAW_DISTANCE),
                () -> setDrawDistance(Game.OPT_DRAW_DISTANCE + 1),
                () -> setDrawDistance(Game.OPT_DRAW_DISTANCE - 1)));

        rows.add(new Row("Fullscreen",
                () -> Game.WINDOW != null && Game.WINDOW.isFullscreen() ? "On" : "Off",
                game::switchMode, null));

        rows.add(new Row("VSync",
                () -> Game.OPT_VSYNC ? "On" : "Off",
                () -> {
                    Game.OPT_VSYNC = !Game.OPT_VSYNC;
                    if (Game.WINDOW != null) {
                        Game.WINDOW.setVSync(Game.OPT_VSYNC);
                    }
                }, null));

        rows.add(new Row("Fog",
                () -> Game.OPT_FOG ? "On" : "Off",
                () -> Game.OPT_FOG = !Game.OPT_FOG, null));

        rows.add(new Row("Cloud Quality",
                () -> Game.QUALITY_LABELS[Game.OPT_CLOUD_QUALITY],
                () -> cycleCloudQuality(1),
                () -> cycleCloudQuality(-1)));

        rows.add(new Row("Cloud Resolution",
                () -> Game.SKY_RESOLUTION_LABELS[skyResIndex()],
                () -> setSkyRes(skyResIndex() + 1),
                () -> setSkyRes(skyResIndex() - 1)));

        rows.add(new Row("Perf Overlay (F6)",
                () -> PERF_OVERLAY_LABELS[Game.OPT_PERF_OVERLAY],
                PerfOverlay::cycle, null));

        rows.add(new Row("Perf Logging",
                () -> PerfLog.isEnabled() ? "perf.log" : "Off",
                () -> PerfLog.setEnabled(!PerfLog.isEnabled()), null));

        rows.add(new Row("Textures",
                () -> Game.OPT_USE_TEXTURES && Game.OPT_DRAW_TEXTURES ? "On" : "Off",
                () -> {
                    boolean on = !(Game.OPT_USE_TEXTURES && Game.OPT_DRAW_TEXTURES);
                    Game.OPT_USE_TEXTURES = on;
                    Game.OPT_DRAW_TEXTURES = on;
                }, null));

        rows.add(new Row("Day Length",
                () -> Game.DAY_LENGTH_LABELS[Game.DAY_LENGTH_INDEX],
                () -> Game.DAY_LENGTH_INDEX =
                        (Game.DAY_LENGTH_INDEX + 1) % Game.DAY_LENGTH_PRESETS.length,
                () -> Game.DAY_LENGTH_INDEX = Math.floorMod(
                        Game.DAY_LENGTH_INDEX - 1, Game.DAY_LENGTH_PRESETS.length)));

        rows.add(new Row("God Rays Quality",
                () -> Game.QUALITY_LABELS[Game.OPT_GOD_RAYS_QUALITY],
                () -> cycleGodRaysQuality(1),
                () -> cycleGodRaysQuality(-1)));

        rows.add(new Row("God Rays Strength",
                () -> String.format("%.0f%%", Game.OPT_GOD_RAYS_INTENSITY_SCALE * 100f),
                () -> Game.OPT_GOD_RAYS_INTENSITY_SCALE =
                        clamp(Game.OPT_GOD_RAYS_INTENSITY_SCALE + 0.10f, 0.50f, 2.00f),
                () -> Game.OPT_GOD_RAYS_INTENSITY_SCALE =
                        clamp(Game.OPT_GOD_RAYS_INTENSITY_SCALE - 0.10f, 0.50f, 2.00f)));

        rows.add(new Row("Time Speed",
                () -> String.format("%.1fx", Game.TIME_SPEED),
                null, null));

        rows.add(new Row("Back to Game", () -> "Esc", () -> Game.setMenuOpen(false), null));
        rows.add(new Row("Save and Exit to Title", () -> "", () -> game.saveAndExit(), null));
    }

    private static final String[] PERF_OVERLAY_LABELS = { "Off", "Frame times", "Shader profile" };

    private static int skyResIndex() {
        for (int i = 0; i < Game.SKY_RESOLUTION_DIVS.length; i++) {
            if (Game.SKY_RESOLUTION_DIVS[i] == Game.OPT_SKY_RESOLUTION_DIV) {
                return i;
            }
        }
        return 1;
    }

    private static void setSkyRes(int index) {
        int n = Game.SKY_RESOLUTION_DIVS.length;
        Game.OPT_SKY_RESOLUTION_DIV = Game.SKY_RESOLUTION_DIVS[Math.floorMod(index, n)];
    }

    private static void cycleCloudQuality(int delta) {
        Game.OPT_CLOUD_QUALITY = Math.floorMod(Game.OPT_CLOUD_QUALITY + delta, 4);
        Game.OPT_CLOUDS = Game.OPT_CLOUD_QUALITY > 0;
        Game.OPT_CLOUD_VOL_STEPS = new int[] { 0, 10, 18, 32 }[Game.OPT_CLOUD_QUALITY];
        Game.OPT_CLOUD_OPACITY_SCALE = 1.0f;
    }

    private static void cycleGodRaysQuality(int delta) {
        Game.OPT_GOD_RAYS_QUALITY = Math.floorMod(Game.OPT_GOD_RAYS_QUALITY + delta, 4);
        Game.OPT_GOD_RAYS = Game.OPT_GOD_RAYS_QUALITY > 0;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void setDrawDistance(int value) {        int clamped = Math.max(Game.OPT_MIN_DRAW_DISTANCE,
                Math.min(Game.OPT_MAX_DRAW_DISTANCE, value));
        if (clamped != Game.OPT_DRAW_DISTANCE) {
            Game.OPT_DRAW_DISTANCE = clamped;
            Game.INSTANCE.setupPerspective();
        }
    }

    /** Updates the hovered row from the cursor position, in pixels. */
    public void updateHover(double mouseX, double mouseY) {
        hovered = panel.rowAt(mouseX, mouseY);
    }

    /** @param secondary true for a right click, which steps values backwards */
    public void click(double mouseX, double mouseY, boolean secondary) {
        int index = panel.rowAt(mouseX, mouseY);
        if (index < 0 || index >= rows.size()) {
            return;
        }
        Row row = rows.get(index);
        if (secondary && row.onRight != null) {
            row.onRight.run();
        } else if (row.onActivate != null) {
            row.onActivate.run();
        }
    }

    public void render() {
        Window window = Game.WINDOW;
        if (window == null) {
            return;
        }
        float w = window.getWidth();
        float h = window.getHeight();

        labels.clear();
        values.clear();
        for (Row row : rows) {
            labels.add(row.label);
            values.add(row.value.get());
        }

        panel.layout(w, h, rows.size());
        panel.render(w, h, "Settings", labels, values, hovered);
    }
}
