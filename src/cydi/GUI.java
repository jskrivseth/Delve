/*
 * Screen-space HUD.
 */
package cydi;

/**
 * Draws the block hotbar: one slot per placeable type, showing the block's atlas
 * icon, with the current mouse-wheel selection highlighted.
 *
 * Geometry is built into reusable arrays so the HUD does not allocate per frame.
 */
public class GUI {

    private static final float SLOT_PX = 46f;
    private static final float GAP_PX = 5f;
    private static final float BOTTOM_MARGIN_PX = 14f;
    private static final float ICON_INSET_PX = 5f;
    private static final float SELECT_GROW_PX = 4f;

    /** 6 vertices per quad, 4 floats per vertex. */
    private static final int FLOATS_PER_QUAD = 6 * 4;

    private final float[] slotQuads;
    private final float[] iconQuads;
    private final float[] selectedQuad = new float[FLOATS_PER_QUAD];
    private final float[] selectedInner = new float[FLOATS_PER_QUAD];
    private final float[] backdropQuad = new float[FLOATS_PER_QUAD];

    public GUI() {
        int slots = Block.PLACEABLE_TYPES.length;
        slotQuads = new float[slots * FLOATS_PER_QUAD];
        iconQuads = new float[slots * FLOATS_PER_QUAD];
    }

    public void render() {
        Window window = Game.WINDOW;
        if (window == null) {
            return;
        }
        float w = window.getWidth();
        float h = window.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        int[] types = Block.PLACEABLE_TYPES;
        int slots = types.length;

        float barWidth = slots * SLOT_PX + (slots - 1) * GAP_PX;
        float startX = (w - barWidth) * 0.5f;
        float top = h - BOTTOM_MARGIN_PX - SLOT_PX;

        int selectedIndex = -1;
        for (int i = 0; i < slots; i++) {
            if (types[i] == Game.SELECTED_BLOCK_TYPE) {
                selectedIndex = i;
            }
        }

        float pad = 6f;
        writeQuad(backdropQuad, 0, startX - pad, top - pad,
                barWidth + pad * 2, SLOT_PX + pad * 2, w, h, 0, 0, 0, 0);

        for (int i = 0; i < slots; i++) {
            float x = startX + i * (SLOT_PX + GAP_PX);
            writeQuad(slotQuads, i * FLOATS_PER_QUAD, x, top, SLOT_PX, SLOT_PX, w, h, 0, 0, 0, 0);

            int type = types[i];
            int col = Block.sideTileCol(type);
            int row = Block.sideTileRow(type);
            float u0 = col / Block.ATLAS_TILE_COUNT;
            float u1 = (col + 1) / Block.ATLAS_TILE_COUNT;
            float v0 = row / Block.ATLAS_TILE_COUNT;
            float v1 = (row + 1) / Block.ATLAS_TILE_COUNT;

            writeQuad(iconQuads, i * FLOATS_PER_QUAD,
                    x + ICON_INSET_PX, top + ICON_INSET_PX,
                    SLOT_PX - ICON_INSET_PX * 2, SLOT_PX - ICON_INSET_PX * 2,
                    w, h, u0, v0, u1, v1);
        }

        Renderer.drawHudQuads(backdropQuad, 6, false, 0.05f, 0.05f, 0.07f, 0.55f);
        Renderer.drawHudQuads(slotQuads, slots * 6, false, 0.28f, 0.28f, 0.32f, 0.80f);

        if (selectedIndex >= 0) {
            float x = startX + selectedIndex * (SLOT_PX + GAP_PX);
            // Outer bright quad plus an inner dark one reads as a selection border.
            writeQuad(selectedQuad, 0,
                    x - SELECT_GROW_PX, top - SELECT_GROW_PX,
                    SLOT_PX + SELECT_GROW_PX * 2, SLOT_PX + SELECT_GROW_PX * 2,
                    w, h, 0, 0, 0, 0);
            writeQuad(selectedInner, 0, x, top, SLOT_PX, SLOT_PX, w, h, 0, 0, 0, 0);
            Renderer.drawHudQuads(selectedQuad, 6, false, 1.0f, 1.0f, 1.0f, 0.95f);
            Renderer.drawHudQuads(selectedInner, 6, false, 0.20f, 0.20f, 0.24f, 1.0f);
        }

        Renderer.drawHudQuads(iconQuads, slots * 6, true, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * Emits two triangles for a pixel-space rectangle, converted to NDC.
     * Pixel Y grows downward, NDC Y grows upward.
     */
    private static void writeQuad(float[] out, int offset,
                                  float px, float py, float pw, float ph,
                                  float screenW, float screenH,
                                  float u0, float v0, float u1, float v1) {
        float x0 = (px / screenW) * 2f - 1f;
        float x1 = ((px + pw) / screenW) * 2f - 1f;
        float y0 = 1f - (py / screenH) * 2f;
        float y1 = 1f - ((py + ph) / screenH) * 2f;

        int i = offset;
        i = put(out, i, x0, y0, u0, v0);
        i = put(out, i, x0, y1, u0, v1);
        i = put(out, i, x1, y1, u1, v1);
        i = put(out, i, x1, y1, u1, v1);
        i = put(out, i, x1, y0, u1, v0);
        put(out, i, x0, y0, u0, v0);
    }

    private static int put(float[] out, int i, float x, float y, float u, float v) {
        out[i] = x;
        out[i + 1] = y;
        out[i + 2] = u;
        out[i + 3] = v;
        return i + 4;
    }
}
