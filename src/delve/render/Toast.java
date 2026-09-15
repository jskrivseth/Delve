package delve.render;

import delve.core.Game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Transient on-screen messages.
 *
 * Until now Game.consoleMsg() wrote into Game.MESSAGES, which nothing drew --
 * notices such as "Running low on memory..." were only ever visible in the
 * console window. That is not good enough for the memory governor, which needs
 * to say out loud that the horizon just shortened because of memory.
 *
 * Messages appear bottom-left, newest at the bottom, each fading out after a
 * few seconds; the newest line also serves as the trigger to redraw.
 */
public final class Toast {

    private static final float FLOATS_PER_QUAD = 6 * 4;
    /** Seconds a message holds at full opacity before it starts dissolving. */
    private static final double HOLD_SECONDS = 3.6;
    /** Seconds the dissolve takes. */
    private static final double FADE_SECONDS = 2.2;
    /** Newest lines drawn. Older console traffic is not worth the screen. */
    private static final int MAX_LINES = 3;

    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private static float[] text = new float[4096];
    private static float[] quads = new float[(int) FLOATS_PER_QUAD * 8];

    private static final class Entry {
        final String message;
        double shownAt;

        Entry(String message, double shownAt) {
            this.message = message;
            this.shownAt = shownAt;
        }
    }

    private Toast() {
    }

    /** Wall clock in seconds; toasts should lapse in real time, not game time. */
    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    /** Queues a message; a repeat of the newest line just refreshes it. */
    public static void show(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        double now = now();
        Entry newest = entries.peekLast();
        if (newest != null && newest.message.equals(message)
                && now - newest.shownAt < HOLD_SECONDS + FADE_SECONDS) {
            newest.shownAt = now;
            return;
        }
        entries.addLast(new Entry(message, now));
        while (entries.size() > MAX_LINES) {
            entries.pollFirst();
        }
    }

    public static void render() {
        if (entries.isEmpty() || Game.WINDOW == null) {
            return;
        }
        float w = Game.WINDOW.getWidth();
        float h = Game.WINDOW.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        double now = now();
        while (!entries.isEmpty()) {
            Entry oldest = entries.peekFirst();
            if (now - oldest.shownAt > HOLD_SECONDS + FADE_SECONDS) {
                entries.pollFirst();
            } else {
                break;
            }
        }
        List<Entry> live = new ArrayList<>(entries);
        if (live.isEmpty()) {
            return;
        }
        float dimmest = 1f;
        for (Entry entry : live) {
            dimmest = Math.min(dimmest, fade(now - entry.shownAt));
        }

        float scale = Math.max(0.22f, Math.min(0.36f, h / 1080f * 0.32f));
        float lineH = TextRenderer.lineHeight(scale);
        float padding = 10f;
        float originX = 12f;
        float originYBottom = h - 12f;

        float widest = 0f;
        for (Entry entry : live) {
            widest = Math.max(widest, TextRenderer.width(entry.message, scale));
        }
        float panelW = widest + padding * 2f;
        float panelH = live.size() * lineH + padding * 2f;
        float panelY = originYBottom - panelH;

        ensureQuads(1);
        int v = 0;
        v += quad(quads, v, originX, panelY, panelW, panelH, w, h);
        Renderer.drawHudQuads(quads, v, 0, 0.04f, 0.05f, 0.07f, 0.72f * dimmest);

        ensureText(live);
        int ti = 0;
        for (int i = 0; i < live.size(); i++) {
            Entry entry = live.get(i);
            float alpha = fade(now - entry.shownAt);
            int totalVerts = 0;
            int written = TextRenderer.emit(text, ti, entry.message,
                    originX + padding, panelY + padding + i * lineH, scale, w, h);
            ti += written * 4;
            totalVerts += written;
            Renderer.drawHudQuads(text, totalVerts, TextRenderer.getTextureId(),
                    0.96f, 0.94f, 0.72f, alpha);
        }
    }

    private static float fade(double ageSeconds) {
        if (ageSeconds <= HOLD_SECONDS) {
            return 1f;
        }
        double remaining = (HOLD_SECONDS + FADE_SECONDS) - ageSeconds;
        return (float) Math.max(0.0, Math.min(1.0, remaining / FADE_SECONDS));
    }

    private static void ensureText(List<Entry> content) {
        int chars = 0;
        for (Entry entry : content) {
            chars += entry.message.length();
        }
        int need = TextRenderer.floatsFor(chars);
        if (text.length < need) {
            text = new float[need];
        }
    }

    private static void ensureQuads(int count) {
        int need = (int) FLOATS_PER_QUAD * count;
        if (quads.length < need) {
            quads = new float[need];
        }
    }

    /** Background rectangle, pixel coordinates converted to NDC. */
    private static int quad(float[] out, int vertexOffset, float px, float py,
                            float pw, float ph, float screenW, float screenH) {
        float x0 = (px / screenW) * 2f - 1f;
        float x1 = ((px + pw) / screenW) * 2f - 1f;
        float y0 = 1f - (py / screenH) * 2f;
        float y1 = 1f - ((py + ph) / screenH) * 2f;

        int i = vertexOffset * 4;
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
