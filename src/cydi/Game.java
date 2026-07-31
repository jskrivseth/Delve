/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.opengl.GL11.GL_LINES;

/**
 * Application shell: owns the window, the frame loop and global tuning options.
 *
 * All fixed-function setup (lighting, fog, matrix modes, texture env) is gone;
 * those responsibilities now live in the shaders driven by {@link Renderer}.
 */
public class Game {

    /*
     * Application options
     */
    public static int APP_SCREEN_WIDTH = 1280;
    public static int APP_SCREEN_HEIGHT = 720;
    private boolean APP_FULLSCREEN = false;
    private static final String APP_WINDOW_TITLE = "Delve";
    public static Window WINDOW;
    /*
     * Player preferences
     */
    public static org.joml.Vector3f PLAYER_START_POSITION =
            new org.joml.Vector3f((World.sizeX / 2) * WorldChunk.sizeX, 3.5f, (World.sizeY / 2) * WorldChunk.sizeZ);
    public static float PLAYER_MOUSE_SENSITIVITY = 0.15f;
    public static float PLAYER_MOVEMENT_SPEED = 2.85f;
    public static float PLAYER_JUMP_FORCE = 0.18f;
    /*
     * Game state
     */
    static FirstPersonCamera GAME_CAMERA;
    static World GAME_WORLD;
    static GUI GUI;
    static Input INPUT;
    public static boolean GAME_FLYMODE = false;
    public static boolean FRUSTUM_CULLING = true;
    public static boolean FIND_SELECTED_BLOCK = true;
    public static Block SELECTED_BLOCK = null;
    public static Block NEW_BLOCK = null;
    /** Block type the player places, cycled with the mouse wheel. */
    public static volatile int SELECTED_BLOCK_TYPE = Block.STONE;
    /*
     * Terrain Generator
     */
    public static int WORLD_SMOOTHINGS = 8;
    public static float WORLD_SMOOTHNESS = 0.325f;
    /*
     * Options
     */
    public static boolean OPT_USE_TEXTURES = true;
    public static boolean OPT_DRAW_TEXTURES = true;
    public static boolean OPT_BLOCK_COLLISION = true;
    public static float[] OPT_CAMERA_DISTANCE_FROM_BLOCKS = new float[]{0.2f, 1.75f, 0.2f};
    public static boolean OPT_SAVE_CHUNKS = false;
    public static boolean OPT_DRAW_COLORED_BLOCKS = true;
    public static boolean OPT_FOG = true;
    private static final float CROSSHAIR_SIZE = 0.03f;
    public static boolean OPT_CULL_CHUNKS = true;
    public static boolean OPT_ONLY_DRAW_EXPOSED_BLOCKS = true;
    public static boolean OPT_DRAW_WIRES = false;
    public static int OPT_DRAW_DISTANCE = 8;
    public static int OPT_MIN_DRAW_DISTANCE = 2;
    public static int OPT_MAX_DRAW_DISTANCE = Math.max(4, (int) Util.logb(Util.getAvailableMemory() / 104857600.0, 1.10));
    public static boolean OPT_VSYNC = true;
    public static int OPT_CHUNK_SERIALIZE_RADIUS_MULTIPLIER = 1;

    /*
     * Debug
     */
    public static boolean DEBUG_DRAW_CAMERA_RAY = false;
    /** 0 and 1 are midnight, 0.25 sunrise, 0.5 noon, 0.75 sunset. */
    public static volatile float TIME_OF_DAY = 0.30f;
    /** Real seconds for one full day. */
    public static float DAY_LENGTH_SECONDS = 480f;
    public static boolean TIME_PAUSED = false;
    /*
     * Stats
     */
    public static String[] MESSAGES = new String[3];
    public static long MEMORY_AVAILBLE = Util.getAvailableMemory();
    public static long MEMORY_MAX = Util.getMaxMemory();
    public static boolean MEMORY_BOUND = false;
    public static long GAME_TIME;
    public static long LAST_FRAME_TIME;
    public static int STAT_SWEPT_CHUNKS = 0;
    public static int STAT_BUILT_CHUNKS = 0;
    public static int FACE_COUNT = 0;
    public static int BLOCK_COUNT = 0;
    public static int LAST_FACE_COUNT = 0;
    public static int LAST_BLOCK_COUNT = 0;
    public static int LAST_VBO_CHUNKS = 0;
    public static long LAST_FRAMES_PER_SECOND = 0;
    public static int FRAME_COUNTER = 0;
    public static int FRAMES_PER_SECOND = 0;

    /** Screen-space crosshair, rebuilt whenever the aspect ratio changes. */
    private final float[] crosshairVerts = new float[12];

    public Game() {
        GAME_WORLD = new World(PLAYER_START_POSITION);
        GAME_CAMERA = GAME_WORLD.camera;
    }

    private void init() {
        WINDOW = new Window(APP_WINDOW_TITLE, APP_SCREEN_WIDTH, APP_SCREEN_HEIGHT);
        WINDOW.create(APP_FULLSCREEN);
        WINDOW.setCursorGrabbed(true);
        WINDOW.setVSync(OPT_VSYNC);

        Renderer.init();
        Renderer.setWireframe(OPT_DRAW_WIRES);

        GAME_WORLD.loadModels();
        GAME_WORLD.loadTextures();
        GUI = new GUI();
        INPUT = new Input(this, WINDOW);

        setupPerspective();
        updateCrosshair();

        printControls();

        GAME_CAMERA.position.y = World.getHeightAt(
                (int) Math.floor(GAME_CAMERA.position.x),
                (int) Math.floor(GAME_CAMERA.position.z)) + OPT_CAMERA_DISTANCE_FROM_BLOCKS[1] + Block.size * 2;
    }

    public void play(boolean fullscreen) {
        this.APP_FULLSCREEN = fullscreen;
        try {
            init();

            getDelta();
            LAST_FRAMES_PER_SECOND = getTime();

            while (!WINDOW.shouldClose()) {
                WINDOW.pollEvents();

                if (WINDOW.consumeResized()) {
                    setupPerspective();
                    updateCrosshair();
                }

                if (WINDOW.isVisible()) {
                    int delta = getDelta();
                    GAME_TIME = delta;
                    update(delta);
                    render();
                    WINDOW.swapBuffers();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void update(long gameTime) {
        // Snapshot last frame's totals before zeroing, so the HUD reports real
        // numbers instead of the counters it is about to reset.
        LAST_FACE_COUNT = FACE_COUNT;
        LAST_BLOCK_COUNT = BLOCK_COUNT;
        LAST_VBO_CHUNKS = World.VBO_CHUNKS;

        FACE_COUNT = 0;
        BLOCK_COUNT = 0;
        if (!TIME_PAUSED) {
            TIME_OF_DAY = (TIME_OF_DAY + (gameTime / 1000.0f) / DAY_LENGTH_SECONDS) % 1.0f;
        }
        Renderer.updateSky(TIME_OF_DAY);
        INPUT.update(gameTime);
        updateFPS();
        GAME_CAMERA.update();
        GAME_WORLD.update();
    }

    public void switchMode() {
        APP_FULLSCREEN = !APP_FULLSCREEN;
        WINDOW.toggleFullscreen();
        setupPerspective();
        updateCrosshair();
    }

    private void render() {
        Renderer.clear();

        GAME_CAMERA.lookThrough();
        Renderer.view().set(Camera.getViewMatrix());
        Renderer.projection().set(Camera.getProjectionMatrix());

        GAME_WORLD.render();

        GUI.render();
        drawCrosshairs();
    }

    /** Builds a crosshair in normalized device coordinates, corrected for aspect. */
    private void updateCrosshair() {
        float aspect = WINDOW == null ? 1.0f : WINDOW.getAspect();
        float halfX = CROSSHAIR_SIZE / 2.0f / aspect;
        float halfY = CROSSHAIR_SIZE / 2.0f;

        crosshairVerts[0] = -halfX; crosshairVerts[1] = 0f;     crosshairVerts[2] = 0f;
        crosshairVerts[3] = halfX;  crosshairVerts[4] = 0f;     crosshairVerts[5] = 0f;
        crosshairVerts[6] = 0f;     crosshairVerts[7] = -halfY; crosshairVerts[8] = 0f;
        crosshairVerts[9] = 0f;     crosshairVerts[10] = halfY; crosshairVerts[11] = 0f;
    }

    private void drawCrosshairs() {
        Renderer.drawOverlayGeometry(GL_LINES, crosshairVerts, 4, 1.0f, 1.0f, 1.0f, 0.85f);
    }

    private static void printControls() {
        System.out.println("""
                === Delve controls ===
                  W/A/S/D      move
                  Mouse        look
                  Space        jump  (double-tap = toggle fly)
                  G            toggle fly mode
                  Space/Shift  fly up / down (while flying)
                  -/=          slower / faster
                  LMB / RMB    break / place block
                  Mouse wheel  choose block to place
                  T            cycle textures      B  vertex colors
                  F            fog                 F3 wireframe
                  [ / ]        rewind / advance time    P pause time
                  F4/F5        draw distance -/+   F7 frustum culling
                  F8           vsync               F11 fullscreen
                  Esc          quit
                """);
    }

    public void setupPerspective() {
        int w = WINDOW == null ? APP_SCREEN_WIDTH : WINDOW.getWidth();
        int h = WINDOW == null ? APP_SCREEN_HEIGHT : WINDOW.getHeight();
        GAME_CAMERA.setup(w, h);
    }

    public long getTime() {
        return (long) (glfwGetTime() * 1000.0);
    }

    public int getDelta() {
        long time = getTime();
        int delta = (int) (time - LAST_FRAME_TIME);
        LAST_FRAME_TIME = time;
        return delta;
    }

    public void updateFPS() {
        if (getTime() - LAST_FRAMES_PER_SECOND > 1000) {
            FRAMES_PER_SECOND = FRAME_COUNTER;
            WINDOW.setTitle(APP_WINDOW_TITLE + " | FPS: " + FRAMES_PER_SECOND
                    + " | faces: " + LAST_FACE_COUNT
                    + " | block: " + Block.nameOf(SELECTED_BLOCK_TYPE)
                    + (GAME_FLYMODE ? " | FLY" : ""));
            FRAME_COUNTER = 0;
            LAST_FRAMES_PER_SECOND += 1000;
            MEMORY_MAX = Util.getMaxMemory();
            MEMORY_AVAILBLE = Util.getAvailableMemory();

            if (((float) MEMORY_AVAILBLE / (float) MEMORY_MAX) < 0.10f) {
                MEMORY_BOUND = true;
                consoleMsg("Running low on memory...");
            } else {
                MEMORY_BOUND = false;
            }
            if (World.SWEEPER_IS_SLEEPING) {
                World.WAKE_SWEEPER = true;
                STAT_SWEPT_CHUNKS = 0;
            }
            STAT_BUILT_CHUNKS = 0;
        }
        FRAME_COUNTER++;
    }

    public static void consoleMsg(String message) {
        MESSAGES[2] = MESSAGES[1];
        MESSAGES[1] = MESSAGES[0];
        MESSAGES[0] = message;
    }

    private static void cleanup() {
        World.threadPool.shutdownNow();
        Renderer.cleanup();
        if (WINDOW != null) {
            WINDOW.destroy();
        }
    }
}
