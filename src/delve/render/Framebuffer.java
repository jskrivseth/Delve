package delve.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Offscreen render target with colour and depth attachments.
 *
 * The scene is rendered here rather than straight to the window so post
 * processing can sample depth, which is what lets god rays know where terrain
 * occludes the sky.
 */
public class Framebuffer {

    private int fbo;
    private int colorTexture;
    private int depthTexture;
    private int width;
    private int height;
    private final boolean floatColor;

    public Framebuffer(int width, int height) {
        this(width, height, false);
    }

    /** @param floatColor true for an RGBA16F colour attachment (HDR history buffers) */
    public Framebuffer(int width, int height, boolean floatColor) {
        this.floatColor = floatColor;
        create(width, height);
    }

    private void create(int w, int h) {
        this.width = Math.max(1, w);
        this.height = Math.max(1, h);

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        if (floatColor) {
            // RGBA16F is colour-renderable in core 3.3, which is what the TAA
            // history ping-pong needs: 8-bit history quantizes the sub-step
            // residuals the whole trick relies on cancelling out.
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, this.width, this.height, 0, GL_RGBA, GL_HALF_FLOAT, (java.nio.ByteBuffer) null);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, this.width, this.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);

        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, this.width, this.height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("Framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Recreates the attachments when the window size changes. */
    public void resize(int w, int h) {
        if (w == width && h == height) {
            return;
        }
        cleanup();
        create(w, h);
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
    }

    public static void unbind(int screenWidth, int screenHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);
    }

    public int getColorTexture() {
        return colorTexture;
    }

    public int getDepthTexture() {
        return depthTexture;
    }

    /** Raw handle, needed for framebuffer-to-framebuffer blits. */
    public int getFbo() {
        return fbo;
    }

    public boolean isComplete() {
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        return status == GL_FRAMEBUFFER_COMPLETE;
    }

    public int getWidth() {
        return width;
    }
    public int getHeight() {
        return height;
    }

    public void cleanup() {
        if (colorTexture != 0) {
            glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (depthTexture != 0) {
            glDeleteTextures(depthTexture);
            depthTexture = 0;
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            fbo = 0;
        }
    }
}
