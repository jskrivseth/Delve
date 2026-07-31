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

    private static final float ROW_H = 40f;
    private static final float ROW_GAP = 6f;
    private static final float PANEL_W = 560f;
    private static final float TITLE_H = 58f;
    private static final float TEXT_SCALE = 0.42f;

    /** Layout resolved per frame so the panel always fits the window. */
    private float rowH = ROW_H;
    private float rowGap = ROW_GAP;
    private float titleH = TITLE_H;
    private float textScale = TEXT_SCALE;
    private float panelW = PANEL_W;

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
    private final Game game;

    private float panelX, panelY;
    private int hovered = -1;

    private float[] quads = new float[4096];
    private float[] text = new float[16384];

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

        rows.add(new Row("Wireframe",
                () -> Game.OPT_DRAW_WIRES ? "On" : "Off",
                () -> {
                    Game.OPT_DRAW_WIRES = !Game.OPT_DRAW_WIRES;
                    Renderer.setWireframe(Game.OPT_DRAW_WIRES);
                }, null));

        rows.add(new Row("Cave Darkness",
                () -> String.format("%.0f%%", (1.0f - Game.OPT_CAVE_MINIMUM_LIGHT / 0.30f) * 100f),
                () -> Game.OPT_CAVE_MINIMUM_LIGHT =
                        Math.max(0.0f, Game.OPT_CAVE_MINIMUM_LIGHT - 0.03f),
                () -> Game.OPT_CAVE_MINIMUM_LIGHT =
                        Math.min(0.30f, Game.OPT_CAVE_MINIMUM_LIGHT + 0.03f)));

        rows.add(new Row("Back to Game", () -> "Esc", () -> Game.MENU_OPEN = false, null));
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

    /** Updates the hovered row from the cursor position, in pixels. */
    public void updateHover(double mouseX, double mouseY) {
        hovered = rowAt(mouseX, mouseY);
    }

    /**
     * Shrinks rows uniformly when the natural layout would overflow the window,
     * so every setting stays reachable at any resolution.
     */
    private void layout(float w, float h) {
        float margin = 16f;
        float natural = TITLE_H + rows.size() * (ROW_H + ROW_GAP) + 12f;
        float available = h - margin * 2f;

        float scale = natural > available ? available / natural : 1f;
        rowH = ROW_H * scale;
        rowGap = ROW_GAP * scale;
        titleH = TITLE_H * scale;
        textScale = TEXT_SCALE * scale;
        panelW = Math.min(PANEL_W, w - margin * 2f);
    }

    private float panelHeight() {
        return titleH + rows.size() * (rowH + rowGap) + 12f * (rowH / ROW_H);
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < panelX || mouseX > panelX + panelW) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            float top = panelY + titleH + i * (rowH + rowGap);
            if (mouseY >= top && mouseY <= top + rowH) {
                return i;
            }
        }
        return -1;
    }

    /** @param secondary true for a right click, which steps values backwards */
    public void click(double mouseX, double mouseY, boolean secondary) {
        int index = rowAt(mouseX, mouseY);
        if (index < 0) {
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

        layout(w, h);
        float panelH = panelHeight();
        panelX = (w - panelW) * 0.5f;
        panelY = (h - panelH) * 0.5f;

        ensureCapacity(rows.size());

        // Dim the world behind the panel.
        int v = 0;
        v += quad(quads, v, 0, 0, w, h, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0f, 0f, 0f, 0.55f);

        v = 0;
        v += quad(quads, v, panelX - 4, panelY - 4, panelW + 8, panelH + 8, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0.85f, 0.85f, 0.90f, 0.30f);

        v = 0;
        v += quad(quads, v, panelX, panelY, panelW, panelH, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0.07f, 0.08f, 0.11f, 0.94f);

        // Rows, with the hovered one lifted.
        v = 0;
        for (int i = 0; i < rows.size(); i++) {
            float top = panelY + titleH + i * (rowH + rowGap);
            v += quad(quads, v, panelX + 10, top, panelW - 20, rowH, w, h);
        }
        Renderer.drawHudQuads(quads, v, 0, 0.16f, 0.17f, 0.21f, 0.95f);

        if (hovered >= 0) {
            float top = panelY + titleH + hovered * (rowH + rowGap);
            v = 0;
            v += quad(quads, v, panelX + 10, top, panelW - 20, rowH, w, h);
            Renderer.drawHudQuads(quads, v, 0, 0.30f, 0.42f, 0.58f, 0.95f);
        }

        int ti = 0;
        String title = "Delve - Settings";
        int written = TextRenderer.emit(text, ti, title,
                panelX + 20, panelY + 10 * (rowH / ROW_H), textScale * 1.2f, w, h);
        ti = advance(ti, written);
        int totalVerts = written;

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float top = panelY + titleH + i * (rowH + rowGap);
            float textY = top + (rowH - TextRenderer.lineHeight(textScale)) * 0.5f;

            written = TextRenderer.emit(text, ti, row.label,
                    panelX + 26, textY, textScale, w, h);
            ti = advance(ti, written);
            totalVerts += written;

            String value = row.value.get();
            float valueX = panelX + panelW - 26 - TextRenderer.width(value, textScale);
            written = TextRenderer.emit(text, ti, value, valueX, textY, textScale, w, h);
            ti = advance(ti, written);
            totalVerts += written;
        }

        Renderer.drawHudQuads(text, totalVerts, TextRenderer.getTextureId(), 0.94f, 0.95f, 0.98f, 1.0f);
    }

    private static int advance(int index, int verticesWritten) {
        return index + verticesWritten * 4;
    }

    private void ensureCapacity(int rowCount) {
        int needQuads = (rowCount + 4) * 6 * 4;
        if (quads.length < needQuads) {
            quads = new float[needQuads];
        }
        int needText = TextRenderer.floatsFor(rowCount * 60 + 40);
        if (text.length < needText) {
            text = new float[needText];
        }
    }

    private static int quad(float[] out, int offset,
                            float px, float py, float pw, float ph,
                            float screenW, float screenH) {
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
