/*
 * Hidden developer tuning overlay, separate from the player-facing Settings
 * menu (Menu.java).
 */
package cydi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * F9-toggled overlay for knobs that only make sense while actively tuning
 * visuals (sun size/glow, cave darkness, ...) rather than as player-facing
 * settings. "Save" writes the current values to {@code dev-tuning.properties}
 * next to the jar, in {@code FIELD_NAME=literal} form, so a developer (or an
 * assistant reading the file) can fold the tuned numbers back into the
 * {@code OPT_*} defaults in {@link Game} without hunting through screenshots.
 */
public class DevMenu {

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

    private static final Path OUTPUT_FILE = Paths.get("dev-tuning.properties");

    private final List<Row> rows = new ArrayList<>();
    private final MenuPanel panel = new MenuPanel();
    private final List<String> labels = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int hovered = -1;
    private String saveStatus = "";

    /** Field name -> raw Java literal, in declaration order, for the save file. */
    private final Map<String, Supplier<String>> persisted = new LinkedHashMap<>();

    public DevMenu() {
        build();
    }

    private void addKnob(String fieldName, String label, Supplier<String> literal,
                          Supplier<String> display, Runnable inc, Runnable dec) {
        rows.add(new Row(label, display, inc, dec));
        persisted.put(fieldName, literal);
    }

    /**
     * A knob whose displayed percentage and step size are relative to a
     * {@code baseline} rather than an absolute value*100 reading. Every
     * knob here started that way (100% == the value the code shipped with),
     * but repeated in-session tuning drifted several of them far from that
     * (e.g. 420%), which stopped meaningfully communicating "how far from
     * normal" a value was. Rebasing so the *current* tuned default reads as
     * 100% keeps that reading meaningful going forward -- absolute min/max
     * are still enforced so a knob can't be pushed to a nonsensical value,
     * they just no longer double as the percentage scale's 0%/100%.
     */
    private void addRelativeKnob(String fieldName, String label, float baseline,
                                  float minAbs, float maxAbs,
                                  Supplier<Float> get, Consumer<Float> set) {
        float step = baseline * 0.05f;
        rows.add(new Row(label,
                () -> String.format("%.0f%%", (get.get() / baseline) * 100f),
                () -> set.accept(clamp(get.get() + step, minAbs, maxAbs)),
                () -> set.accept(clamp(get.get() - step, minAbs, maxAbs))));
        persisted.put(fieldName, () -> get.get() + "f");
    }

    private void build() {
        addRelativeKnob("OPT_SUN_SIZE_SCALE", "Sun Size", 0.80f, 0.30f, 2.00f,
                () -> Game.OPT_SUN_SIZE_SCALE, v -> Game.OPT_SUN_SIZE_SCALE = v);

        addRelativeKnob("OPT_SUN_GLOW_SCALE", "Sun Glow", 0.30f, 0.10f, 2.00f,
                () -> Game.OPT_SUN_GLOW_SCALE, v -> Game.OPT_SUN_GLOW_SCALE = v);

        addKnob("OPT_CAVE_MINIMUM_LIGHT", "Cave Darkness",
                () -> Game.OPT_CAVE_MINIMUM_LIGHT + "f",
                () -> String.format("%.0f%%", (1.0f - Game.OPT_CAVE_MINIMUM_LIGHT / 0.30f) * 100f),
                () -> Game.OPT_CAVE_MINIMUM_LIGHT = clamp(Game.OPT_CAVE_MINIMUM_LIGHT - 0.03f, 0.0f, 0.30f),
                () -> Game.OPT_CAVE_MINIMUM_LIGHT = clamp(Game.OPT_CAVE_MINIMUM_LIGHT + 0.03f, 0.0f, 0.30f));

        addRelativeKnob("OPT_CLOUD_LAYER_SPACING", "Cloud Layer Spacing", 3.00f, 0.60f, 9.00f,
                () -> Game.OPT_CLOUD_LAYER_SPACING, v -> Game.OPT_CLOUD_LAYER_SPACING = v);

        addRelativeKnob("OPT_CLOUD_INTERLAYER_SHADOW", "Cloud Inter-Layer Shadow", 0.80f, 0.00f, 2.40f,
                () -> Game.OPT_CLOUD_INTERLAYER_SHADOW, v -> Game.OPT_CLOUD_INTERLAYER_SHADOW = v);

        addRelativeKnob("OPT_CLOUD_UNDERGLOW_SCALE_L0", "Cloud Underglow L0", 2.80f, 0.00f, 8.40f,
                () -> Game.OPT_CLOUD_UNDERGLOW_SCALE_L0, v -> Game.OPT_CLOUD_UNDERGLOW_SCALE_L0 = v);

        addRelativeKnob("OPT_CLOUD_UNDERGLOW_SCALE_L1", "Cloud Underglow L1", 4.20f, 0.00f, 12.60f,
                () -> Game.OPT_CLOUD_UNDERGLOW_SCALE_L1, v -> Game.OPT_CLOUD_UNDERGLOW_SCALE_L1 = v);

        addRelativeKnob("OPT_CLOUD_UNDERGLOW_SCALE_L2", "Cloud Underglow L2", 1.20f, 0.00f, 3.60f,
                () -> Game.OPT_CLOUD_UNDERGLOW_SCALE_L2, v -> Game.OPT_CLOUD_UNDERGLOW_SCALE_L2 = v);

        addRelativeKnob("OPT_CLOUD_TRANSLUCENCY_CONTRAST", "Cloud Translucency Contrast", 0.80f, 0.00f, 2.40f,
                () -> Game.OPT_CLOUD_TRANSLUCENCY_CONTRAST, v -> Game.OPT_CLOUD_TRANSLUCENCY_CONTRAST = v);

        addRelativeKnob("OPT_CHUNK_FADE_DURATION_MS", "Chunk Fade Duration", 1500.0f, 200.0f, 6000.0f,
                () -> Game.OPT_CHUNK_FADE_DURATION_MS, v -> Game.OPT_CHUNK_FADE_DURATION_MS = v);

        addRelativeKnob("OPT_CHUNK_EDGE_FADE_FRACTION", "Chunk Edge Fade Width", 0.35f, 0.05f, 0.90f,
                () -> Game.OPT_CHUNK_EDGE_FADE_FRACTION, v -> Game.OPT_CHUNK_EDGE_FADE_FRACTION = v);

        rows.add(new Row("Save to dev-tuning.properties", () -> saveStatus, this::save, null));
        rows.add(new Row("Close (F9)", () -> "", () -> Game.setDevMenuOpen(false), null));
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Written by the in-game developer tuning menu (F9).\n");
        sb.append("# Fold these into the matching OPT_* defaults in Game.java.\n");
        for (Map.Entry<String, Supplier<String>> entry : persisted.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue().get()).append('\n');
        }
        try {
            Files.write(OUTPUT_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
            saveStatus = "Saved";
            System.out.println("[DevMenu] Wrote tuning values to " + OUTPUT_FILE.toAbsolutePath());
        } catch (IOException ex) {
            saveStatus = "Save failed";
            System.err.println("[DevMenu] Failed to write " + OUTPUT_FILE + ": " + ex.getMessage());
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
        panel.render(w, h, "Developer Tuning (F9)", labels, values, hovered);
    }
}
