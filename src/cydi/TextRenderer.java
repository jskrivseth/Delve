package cydi;

import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Bitmap text rendering.
 *
 * Glyphs are rasterised once at startup with AWT into a single GL texture, which
 * avoids shipping a font atlas asset and keeps the HUD to one draw call per
 * string. Slick2D previously provided this and was removed with the fixed
 * function pipeline.
 */
public class TextRenderer {

    private static final int FIRST_CHAR = 32;
    private static final int LAST_CHAR = 126;
    private static final int GLYPH_COUNT = LAST_CHAR - FIRST_CHAR + 1;
    private static final int COLUMNS = 12;
    private static final int FONT_SIZE = 44;
    /** Padding around each glyph so linear filtering cannot bleed neighbours. */
    private static final int PAD = 3;

    private static int textureId;
    private static int atlasSize;
    private static int cellW, cellH;
    private static float ascent;
    private static final float[] advance = new float[GLYPH_COUNT];
    private static final float[] glyphWidth = new float[GLYPH_COUNT];

    public static void init() {
        Font font = pickFont();

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();

        int maxAdvance = 0;
        for (int c = FIRST_CHAR; c <= LAST_CHAR; c++) {
            int w = fm.charWidth((char) c);
            advance[c - FIRST_CHAR] = w;
            glyphWidth[c - FIRST_CHAR] = w;
            maxAdvance = Math.max(maxAdvance, w);
        }
        ascent = fm.getAscent();
        cellW = maxAdvance + PAD * 2;
        cellH = fm.getHeight() + PAD * 2;
        pg.dispose();

        int rows = (GLYPH_COUNT + COLUMNS - 1) / COLUMNS;
        atlasSize = nextPowerOfTwo(Math.max(cellW * COLUMNS, cellH * rows));

        BufferedImage atlas = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE);

        for (int i = 0; i < GLYPH_COUNT; i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = col * cellW + PAD;
            int y = row * cellH + PAD;
            g.drawString(String.valueOf((char) (FIRST_CHAR + i)), x, y + ascent);
        }
        g.dispose();

        ByteBuffer pixels = MemoryUtil.memAlloc(atlasSize * atlasSize * 4);
        for (int y = 0; y < atlasSize; y++) {
            for (int x = 0; x < atlasSize; x++) {
                int argb = atlas.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                // Store white with the glyph coverage in alpha so the shader can
                // tint text to any colour.
                pixels.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) a);
            }
        }
        pixels.flip();

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasSize, atlasSize, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixels);
    }

    private static Font pickFont() {
        // Prefer a clean monospaced face, falling back to whatever is available.
        for (String name : new String[]{"Consolas", "Segoe UI Semibold", "Arial", Font.SANS_SERIF}) {
            Font f = new Font(name, Font.BOLD, FONT_SIZE);
            if (f.getFamily().equalsIgnoreCase(name) || Font.SANS_SERIF.equals(name)) {
                return f;
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE);
    }

    private static int nextPowerOfTwo(int v) {
        int n = 1;
        while (n < v) {
            n <<= 1;
        }
        return n;
    }

    public static int getTextureId() {
        return textureId;
    }

    /** Rendered height of one line at the given scale, in pixels. */
    public static float lineHeight(float scale) {
        return cellH * scale;
    }

    /** Rendered width of a string at the given scale, in pixels. */
    public static float width(String text, float scale) {
        float total = 0;
        for (int i = 0; i < text.length(); i++) {
            int idx = text.charAt(i) - FIRST_CHAR;
            if (idx >= 0 && idx < GLYPH_COUNT) {
                total += advance[idx];
            }
        }
        return total * scale;
    }

    /** Floats needed to hold the quads for a string of the given length. */
    public static int floatsFor(int length) {
        return length * 6 * 4;
    }

    /**
     * Appends the quads for one string, converting pixel coordinates to NDC.
     *
     * @return the number of vertices written
     */
    public static int emit(float[] out, int offset, String text,
                           float px, float py, float scale,
                           float screenW, float screenH) {
        int verts = 0;
        int i = offset;
        float penX = px;

        for (int c = 0; c < text.length(); c++) {
            int idx = text.charAt(c) - FIRST_CHAR;
            if (idx < 0 || idx >= GLYPH_COUNT) {
                continue;
            }
            int col = idx % COLUMNS;
            int row = idx / COLUMNS;

            float w = glyphWidth[idx] * scale;
            float h = cellH * scale;

            if (w > 0) {
                float u0 = (col * cellW + PAD) / (float) atlasSize;
                float u1 = (col * cellW + PAD + glyphWidth[idx]) / (float) atlasSize;
                float v0 = (row * cellH) / (float) atlasSize;
                float v1 = (row * cellH + cellH) / (float) atlasSize;

                float x0 = (penX / screenW) * 2f - 1f;
                float x1 = ((penX + w) / screenW) * 2f - 1f;
                float y0 = 1f - (py / screenH) * 2f;
                float y1 = 1f - ((py + h) / screenH) * 2f;

                i = put(out, i, x0, y0, u0, v0);
                i = put(out, i, x0, y1, u0, v1);
                i = put(out, i, x1, y1, u1, v1);
                i = put(out, i, x1, y1, u1, v1);
                i = put(out, i, x1, y0, u1, v0);
                i = put(out, i, x0, y0, u0, v0);
                verts += 6;
            }
            penX += advance[idx] * scale;
        }
        return verts;
    }

    private static int put(float[] out, int i, float x, float y, float u, float v) {
        out[i] = x;
        out[i + 1] = y;
        out[i + 2] = u;
        out[i + 3] = v;
        return i + 4;
    }

    public static void cleanup() {
        if (textureId != 0) {
            glDeleteTextures(textureId);
            textureId = 0;
        }
    }
}
