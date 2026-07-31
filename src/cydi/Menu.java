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

        rows.add(new Row("Fog Density",
                () -> String.format("%.0f%%", Game.OPT_FOG_DENSITY * 100f),
                () -> Game.OPT_FOG_DENSITY = clamp(Game.OPT_FOG_DENSITY + 0.10f, 0.30f, 2.50f),
                () -> Game.OPT_FOG_DENSITY = clamp(Game.OPT_FOG_DENSITY - 0.10f, 0.30f, 2.50f)));

        rows.add(new Row("Fog Persistence",
                () -> String.format("%.0f%%", Game.OPT_FOG_PERSISTENCE * 100f),
                () -> Game.OPT_FOG_PERSISTENCE = clamp(Game.OPT_FOG_PERSISTENCE + 0.10f, 0.00f, 2.50f),
                () -> Game.OPT_FOG_PERSISTENCE = clamp(Game.OPT_FOG_PERSISTENCE - 0.10f, 0.00f, 2.50f)));

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

        rows.add(new Row("Time",
                () -> Game.TIME_PAUSED ? "Paused" : clockString(),
                () -> Game.TIME_PAUSED = !Game.TIME_PAUSED, null));

        rows.add(new Row("Moon Phase",
                () -> Renderer.MOON_PHASES[Renderer.getMoonPhase()], null, null));

        rows.add(new Row("Flashlight",
                () -> Game.OPT_FLASHLIGHT ? "On" : "Off",
                () -> Game.OPT_FLASHLIGHT = !Game.OPT_FLASHLIGHT, null));

        rows.add(new Row("Frustum Culling",
                () -> Game.FRUSTUM_CULLING ? "On" : "Off",
                () -> Game.FRUSTUM_CULLING = !Game.FRUSTUM_CULLING, null));

        rows.add(new Row("Cave Darkness",
                () -> String.format("%.0f%%", (1.0f - Game.OPT_CAVE_MINIMUM_LIGHT / 0.30f) * 100f),
                () -> Game.OPT_CAVE_MINIMUM_LIGHT =
                        Math.max(0.0f, Game.OPT_CAVE_MINIMUM_LIGHT - 0.03f),
                () -> Game.OPT_CAVE_MINIMUM_LIGHT =
                        Math.min(0.30f, Game.OPT_CAVE_MINIMUM_LIGHT + 0.03f)));

        rows.add(new Row("God Rays",
                () -> Game.OPT_GOD_RAYS ? "On" : "Off",
                () -> Game.OPT_GOD_RAYS = !Game.OPT_GOD_RAYS, null));

        rows.add(new Row("Texture Pack",
                Renderer::getTexturePackName,
                () -> Renderer.cycleTexturePack(1),
                () -> Renderer.cycleTexturePack(-1)));

        rows.add(new Row("Rescan Packs",
                () -> String.valueOf(Renderer.getTexturePacks().size()),
                Renderer::rescanTexturePacks, null));

        rows.add(new Row("Back to Game", () -> "Esc", () -> Game.setMenuOpen(false), null));
        rows.add(new Row("Save and Exit to Title", () -> "", () -> game.saveAndExit(), null));
    }

    private static String clockString() {
        float t = Game.TIME_OF_DAY;
        int minutes = (int) (t * 24 * 60);
        return String.format("Day %d  %02d:%02d", Game.DAY_COUNT + 1, minutes / 60, minutes % 60);
    }

    private static void setDrawDistance(int value) {
        int clamped = Math.max(Game.OPT_MIN_DRAW_DISTANCE,
                Math.min(Game.OPT_MAX_DRAW_DISTANCE, value));
        if (clamped != Game.OPT_DRAW_DISTANCE) {
            Game.OPT_DRAW_DISTANCE = clamped;
            Game.INSTANCE.setupPerspective();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
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
