/*
 * Shared panel rendering for the menu screens.
 */
package cydi;

import java.util.List;

/**
 * Draws a titled list panel and resolves clicks against it.
 *
 * The settings menu, title screen and world picker all present the same shape,
 * so layout, scaling and hit testing live here rather than being repeated.
 */
public final class MenuPanel {

    private static final float ROW_H = 40f;
    private static final float ROW_GAP = 6f;
    private static final float PANEL_W = 560f;
    private static final float TITLE_H = 58f;
    private static final float TEXT_SCALE = 0.42f;
    private static final int FLOATS_PER_QUAD = 6 * 4;

    private float rowH = ROW_H;
    private float rowGap = ROW_GAP;
    private float titleH = TITLE_H;
    private float textScale = TEXT_SCALE;
    private float panelW = PANEL_W;
    private float panelX, panelY;
    private int rowCount;

    private float[] quads = new float[FLOATS_PER_QUAD * 8];
    private float[] text = new float[16384];

    /** Recomputes layout for the current window and row count. */
    public void layout(float w, float h, int rows) {
        rowCount = rows;
        float margin = 16f;
        float natural = TITLE_H + rows * (ROW_H + ROW_GAP) + 12f;
        float available = h - margin * 2f;

        float scale = natural > available ? available / natural : 1f;
        rowH = ROW_H * scale;
        rowGap = ROW_GAP * scale;
        titleH = TITLE_H * scale;
        textScale = TEXT_SCALE * scale;
        panelW = Math.min(PANEL_W, w - margin * 2f);

        float panelH = panelHeight();
        panelX = (w - panelW) * 0.5f;
        panelY = (h - panelH) * 0.5f;

        int needQuads = (rows + 4) * FLOATS_PER_QUAD;
        if (quads.length < needQuads) {
            quads = new float[needQuads];
        }
        int needText = TextRenderer.floatsFor(rows * 70 + 60);
        if (text.length < needText) {
            text = new float[needText];
        }
    }

    private float panelHeight() {
        return titleH + rowCount * (rowH + rowGap) + 12f * (rowH / ROW_H);
    }

    /** Index of the row under the cursor, or -1. */
    public int rowAt(double mouseX, double mouseY) {
        if (mouseX < panelX || mouseX > panelX + panelW) {
            return -1;
        }
        for (int i = 0; i < rowCount; i++) {
            float top = panelY + titleH + i * (rowH + rowGap);
            if (mouseY >= top && mouseY <= top + rowH) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param values may be null for screens with no right-hand column
     */
    public void render(float w, float h, String title,
                       List<String> labels, List<String> values, int hovered) {
        float panelH = panelHeight();

        int v = 0;
        v += quad(quads, v, 0, 0, w, h, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0f, 0f, 0f, 0.62f);

        v = 0;
        v += quad(quads, v, panelX - 4, panelY - 4, panelW + 8, panelH + 8, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0.85f, 0.85f, 0.90f, 0.30f);

        v = 0;
        v += quad(quads, v, panelX, panelY, panelW, panelH, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0.07f, 0.08f, 0.11f, 0.94f);

        v = 0;
        for (int i = 0; i < rowCount; i++) {
            float top = panelY + titleH + i * (rowH + rowGap);
            v += quad(quads, v, panelX + 10, top, panelW - 20, rowH, w, h);
        }
        Renderer.drawHudQuads(quads, v, 0, 0.16f, 0.17f, 0.21f, 0.95f);

        if (hovered >= 0 && hovered < rowCount) {
            float top = panelY + titleH + hovered * (rowH + rowGap);
            v = 0;
            v += quad(quads, v, panelX + 10, top, panelW - 20, rowH, w, h);
            Renderer.drawHudQuads(quads, v, 0, 0.30f, 0.42f, 0.58f, 0.95f);
        }

        int ti = 0;
        int written = TextRenderer.emit(text, ti, title,
                panelX + 20, panelY + 10f * (rowH / ROW_H), textScale * 1.2f, w, h);
        ti += written * 4;
        int totalVerts = written;

        for (int i = 0; i < rowCount; i++) {
            float top = panelY + titleH + i * (rowH + rowGap);
            float textY = top + (rowH - TextRenderer.lineHeight(textScale)) * 0.5f;

            written = TextRenderer.emit(text, ti, labels.get(i),
                    panelX + 26, textY, textScale, w, h);
            ti += written * 4;
            totalVerts += written;

            if (values != null) {
                String value = values.get(i);
                if (value != null && !value.isEmpty()) {
                    float valueX = panelX + panelW - 26 - TextRenderer.width(value, textScale);
                    written = TextRenderer.emit(text, ti, value, valueX, textY, textScale, w, h);
                    ti += written * 4;
                    totalVerts += written;
                }
            }
        }

        Renderer.drawHudQuads(text, totalVerts, TextRenderer.getTextureId(),
                0.94f, 0.95f, 0.98f, 1.0f);
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
