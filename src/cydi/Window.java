package cydi;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Owns the GLFW window and the OpenGL 3.3 core profile context.
 * Replaces the LWJGL 2 Display/DisplayMode/Sys stack.
 */
public class Window {

    private long handle = NULL;
    private int width;
    private int height;
    private boolean fullscreen;
    private boolean resized;
    private final String title;

    public Window(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    public void create(boolean fullscreen) {
        this.fullscreen = fullscreen;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_DEPTH_BITS, 24);
        glfwWindowHint(GLFW_SAMPLES, 4);

        long monitor = fullscreen ? glfwGetPrimaryMonitor() : NULL;
        if (fullscreen) {
            GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (mode != null) {
                this.width = mode.width();
                this.height = mode.height();
            }
        }

        handle = glfwCreateWindow(width, height, title, monitor, NULL);
        if (handle == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> {
            if (w > 0 && h > 0) {
                this.width = w;
                this.height = h;
                this.resized = true;
            }
        });

        if (!fullscreen) {
            GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (mode != null) {
                glfwSetWindowPos(handle, (mode.width() - width) / 2, (mode.height() - height) / 2);
            }
        }

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        setVSync(true);
        glfwShowWindow(handle);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            this.width = w.get(0);
            this.height = h.get(0);
        }
        glViewport(0, 0, width, height);

        System.out.println("OpenGL " + glGetString(GL_VERSION) + " | " + glGetString(GL_RENDERER));
    }

    public void setVSync(boolean enabled) {
        glfwSwapInterval(enabled ? 1 : 0);
    }

    /** Locks the cursor to the window for FPS-style mouse look. */
    public void setCursorGrabbed(boolean grabbed) {
        glfwSetInputMode(handle, GLFW_CURSOR, grabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (grabbed && glfwRawMouseMotionSupported()) {
            glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }
    }

    public void toggleFullscreen() {
        boolean target = !fullscreen;
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (mode == null) {
            return;
        }
        if (target) {
            glfwSetWindowMonitor(handle, glfwGetPrimaryMonitor(), 0, 0, mode.width(), mode.height(), mode.refreshRate());
        } else {
            int w = Game.APP_SCREEN_WIDTH;
            int h = Game.APP_SCREEN_HEIGHT;
            glfwSetWindowMonitor(handle, NULL, (mode.width() - w) / 2, (mode.height() - h) / 2, w, h, GLFW_DONT_CARE);
        }
        fullscreen = target;
        setVSync(Game.OPT_VSYNC);
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    /** Consumes the pending resize flag, updating the GL viewport if needed. */
    public boolean consumeResized() {
        if (!resized) {
            return false;
        }
        resized = false;
        glViewport(0, 0, width, height);
        return true;
    }

    public boolean isVisible() {
        return glfwGetWindowAttrib(handle, GLFW_ICONIFIED) == GLFW_FALSE;
    }

    public void setTitle(String value) {
        glfwSetWindowTitle(handle, value);
    }

    public long handle() {
        return handle;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float getAspect() {
        return height == 0 ? 1.0f : (float) width / (float) height;
    }

    public void destroy() {
        if (handle != NULL) {
            glfwFreeCallbacks(handle);
            glfwDestroyWindow(handle);
            handle = NULL;
        }
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
    }

    private static void glfwFreeCallbacks(long window) {
        org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
    }
}
