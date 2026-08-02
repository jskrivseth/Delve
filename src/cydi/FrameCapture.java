package cydi;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * Periodic frame capture for visual inspection while profiling.
 */
public final class FrameCapture {

    private static final long INTERVAL_MS = 1_000;
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Path OUTPUT_DIR = Paths.get("perf-frames");

    private static boolean enabled;
    private static long lastCaptureMs;

    private FrameCapture() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(OUTPUT_DIR);
            System.out.println("[perf] frame capture to " + OUTPUT_DIR.toAbsolutePath());
        } catch (IOException e) {
            enabled = false;
            System.err.println("Frame capture disabled: " + e.getMessage());
        }
    }

    public static void captureMaybe() {
        if (!enabled || Game.SCREEN != Game.Screen.PLAYING || Game.WINDOW == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCaptureMs < INTERVAL_MS) {
            return;
        }
        lastCaptureMs = now;
        captureNow();
    }

    private static void captureNow() {
        int w = Game.WINDOW.getWidth();
        int h = Game.WINDOW.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        glReadBuffer(GL_BACK);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int srcY = h - 1 - y;
            for (int x = 0; x < w; x++) {
                int i = (srcY * w + x) * 4;
                int r = pixels.get(i) & 0xFF;
                int g = pixels.get(i + 1) & 0xFF;
                int b = pixels.get(i + 2) & 0xFF;
                int a = pixels.get(i + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        Path output = OUTPUT_DIR.resolve("frame-" + LocalDateTime.now().format(FILE_STAMP) + ".png");
        try {
            ImageIO.write(image, "png", output.toFile());
        } catch (IOException e) {
            enabled = false;
            System.err.println("Frame capture disabled: " + e.getMessage());
        }
    }
}
