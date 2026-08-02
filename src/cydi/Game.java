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
    public static float PLAYER_BASE_MOVEMENT_SPEED = 2.85f;
    public static float PLAYER_FLY_SPEED_BONUS = 2.0f;
    public static float PLAYER_MOVEMENT_SPEED = 2.85f;
    public static float PLAYER_JUMP_FORCE = 0.18f;
    /*
     * Game state
     */
    static FirstPersonCamera GAME_CAMERA;
    static World GAME_WORLD;
    static GUI GUI;
    static Input INPUT;
    static Menu MENU;
    static DevMenu DEV_MENU;
    static TitleScreen TITLE;
    /** The running game, so menu actions can reach instance methods. */
    static Game INSTANCE;
    /** When true the settings overlay is up and world input is suspended. */
    public static boolean MENU_OPEN = false;
    /** When true the hidden developer tuning overlay (F9) is up. */
    public static boolean DEV_MENU_OPEN = false;

    /** Which screen currently owns input and rendering. */
    public enum Screen { TITLE, PLAYING }

    public static Screen SCREEN = Screen.TITLE;
    /** The world being played, or null while on the title screen. */
    public static SaveGame CURRENT_SAVE;
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
    public static int OPT_DRAW_DISTANCE = 10;
    public static int OPT_MIN_DRAW_DISTANCE = 2;
    public static int OPT_MAX_DRAW_DISTANCE = Math.max(4, (int) Util.logb(Util.getAvailableMemory() / 104857600.0, 1.10));
    public static boolean OPT_VSYNC = true;
    public static int OPT_CHUNK_SERIALIZE_RADIUS_MULTIPLIER = 1;
    public static boolean OPT_AMBIENT_OCCLUSION = true;
    public static boolean OPT_ANTIALIASING = true;
    public static boolean OPT_FLASHLIGHT = false;
    /** God ray quality preset: 0 off, 1 low, 2 med, 3 high. */
    public static int OPT_GOD_RAYS_QUALITY = 3;
    public static boolean OPT_GOD_RAYS = true;
    /** Sun disc size multiplier. */
    public static float OPT_SUN_SIZE_SCALE = 0.80f;
    /** Sun glow multiplier. */
    public static float OPT_SUN_GLOW_SCALE = 0.45f;
    /** God ray intensity multiplier. */
    public static float OPT_GOD_RAYS_INTENSITY_SCALE = 1.00f;
    /** Cloud quality preset: 0 off, 1 low, 2 med, 3 high. */
    public static int OPT_CLOUD_QUALITY = 3;
    /** Cloud volumetric rendering steps (8-32) - derived from cloud quality. */
    public static int OPT_CLOUD_VOL_STEPS = 32;
    /** Scales the preset cloud opacity. Higher makes clouds darker/denser. */
    public static float OPT_CLOUD_OPACITY_SCALE = 1.0f;
    /** Scales how strongly clouds dim direct sunlight. */
    public static float OPT_CLOUD_SHADOW_SCALE = 1.0f;
    /** Whether to enable volumetric clouds (distinct from fog). */
    public static boolean OPT_CLOUDS = true;
    /**
     * Divisor for the resolution the sky and volumetric clouds are marched at.
     * The march is the most expensive per-pixel work in the renderer and the sky
     * is low frequency, so halving resolution quarters the cost for little
     * visible loss. 1 = full, 2 = half, 4 = quarter.
     */
    public static int OPT_SKY_RESOLUTION_DIV = 1;
    public static final int[] SKY_RESOLUTION_DIVS = { 1, 2, 4 };
    public static final String[] SKY_RESOLUTION_LABELS = { "Full", "Half", "Quarter" };
    public static final String[] QUALITY_LABELS = { "Off", "Low", "Med", "High" };
    /**
     * Performance overlay level: 0 off, 1 frame timings, 2 adds per-shader GPU
     * times and ablation-based per-function cost. Level 2 deliberately toggles
     * cloud features on and off to measure them, so the sky flickers while it
     * runs.
     */
    public static int OPT_PERF_OVERLAY = 0;
    /** Time passage speed multiplier (0.5 = half normal, 2.0 = double speed). */
    public static float TIME_SPEED = 1.0f;
    /** Whether weather cycles are active for natural cloud variation. */
    public static boolean DAYS_WITH_WEATHER_CYCLES = true;
    /** User multiplier for global fog thickness (2.5 = tuned default). */
    public static float OPT_FOG_DENSITY = 2.5f;
    /** User multiplier for near persistent valley mist (2.5 = tuned default). */
    public static float OPT_FOG_PERSISTENCE = 2.5f;
    /** Light floor for fully enclosed spaces, so caves stay navigable. */
    public static float OPT_CAVE_MINIMUM_LIGHT = 0.09f;

    /*
     * Debug
     */
    public static boolean DEBUG_DRAW_CAMERA_RAY = false;
    /** 0 and 1 are midnight, 0.25 sunrise, 0.5 noon, 0.75 sunset. */
    public static volatile float TIME_OF_DAY = 0.30f;
    /** Selectable day lengths in real seconds. */
    public static final float[] DAY_LENGTH_PRESETS = {60f, 300f, 600f, 900f, 1800f, 3600f};
    public static final String[] DAY_LENGTH_LABELS = {"1 min", "5 min", "10 min", "15 min", "30 min", "60 min"};
    public static int DAY_LENGTH_INDEX = 3;
    public static boolean TIME_PAUSED = false;
    /** Whole days elapsed, which advances the moon phase. */
    public static int DAY_COUNT = 0;

    public static float dayLengthSeconds() {
        return DAY_LENGTH_PRESETS[DAY_LENGTH_INDEX];
    }
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
        INSTANCE = this;
    }

    private void init() {
        WINDOW = new Window(APP_WINDOW_TITLE, APP_SCREEN_WIDTH, APP_SCREEN_HEIGHT);
        WINDOW.create(APP_FULLSCREEN);
        WINDOW.setVSync(OPT_VSYNC);

        Renderer.init();
        Renderer.setWireframe(OPT_DRAW_WIRES);
        TextRenderer.init();

        GUI = new GUI();
        INPUT = new Input(this, WINDOW);
        MENU = new Menu(this);
        DEV_MENU = new DevMenu();
        TITLE = new TitleScreen();

        printControls();

        // Start on the title screen with the cursor free.
        SCREEN = Screen.TITLE;
        WINDOW.setCursorGrabbed(false);

        // After the title screen is established, so an auto-started world is not
        // immediately reset back to it.
        applyProfilingFlags();
    }

    /**
     * Enables profiling straight from the command line, so a slow session can be
     * captured without first navigating menus:
     * {@code java -Ddelve.perf=2 -Ddelve.perflog=true -Ddelve.perfshots=true -jar delve.jar}
     * {@code -Ddelve.fullscreen=true -Ddelve.skydiv=1 -Ddelve.cloudquality=high} force the
     * worst-case render settings without touching the settings menu.
     */
    /**
     * Reads a boolean launch flag. {@link Boolean#parseBoolean} only accepts
     * "true", so the obvious {@code -Ddelve.perflog=1} silently did nothing.
     */
    private static boolean flag(String key) {
        String v = System.getProperty(key);
        if (v == null) {
            return false;
        }
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equals("1")
                || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    private static int quality(String key, int fallback) {
        String v = System.getProperty(key);
        if (v == null) {
            return fallback;
        }
        v = v.trim();
        if (v.equalsIgnoreCase("off")) return 0;
        if (v.equalsIgnoreCase("low")) return 1;
        if (v.equalsIgnoreCase("med") || v.equalsIgnoreCase("medium")) return 2;
        if (v.equalsIgnoreCase("high")) return 3;
        try {
            return Math.max(0, Math.min(3, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            System.err.println("Ignoring bad " + key + " value: " + v);
            return fallback;
        }
    }

    private static void applyProfilingFlags() {
        String level = System.getProperty("delve.perf");
        boolean perfLog = flag("delve.perflog");
        if (level != null) {
            try {
                OPT_PERF_OVERLAY = Math.max(0, Math.min(2, Integer.parseInt(level.trim())));
            } catch (NumberFormatException e) {
                System.err.println("Ignoring bad delve.perf value: " + level);
            }
            GpuProfiler.setEnabled(OPT_PERF_OVERLAY != PerfOverlay.OFF);
            ShaderProfiler.setRunning(OPT_PERF_OVERLAY == PerfOverlay.FULL);
        }
        if (perfLog) {
            PerfLog.setEnabled(true);
        }
        boolean perfShots = System.getProperty("delve.perfshots") == null
                ? perfLog : flag("delve.perfshots");
        if (perfShots) {
            FrameCapture.setEnabled(true);
        }
        // Full-screen, full-resolution, high-detail is the worst case this
        // whole workstream is optimizing for, and it is otherwise only
        // reachable by clicking through the settings menu after launch.
        if (System.getProperty("delve.fullscreen") != null
                && flag("delve.fullscreen") != WINDOW.isFullscreen()) {
            WINDOW.toggleFullscreen();
        }
        String skyDiv = System.getProperty("delve.skydiv");
        if (skyDiv != null) {
            try {
                OPT_SKY_RESOLUTION_DIV = Math.max(1, Integer.parseInt(skyDiv.trim()));
            } catch (NumberFormatException e) {
                System.err.println("Ignoring bad delve.skydiv value: " + skyDiv);
            }
        }
        OPT_GOD_RAYS_QUALITY = quality("delve.godrays", OPT_GOD_RAYS_QUALITY);
        OPT_CLOUD_QUALITY = quality("delve.cloudquality", OPT_CLOUD_QUALITY);
        OPT_GOD_RAYS = OPT_GOD_RAYS_QUALITY > 0;
        OPT_CLOUDS = OPT_CLOUD_QUALITY > 0;
        OPT_CLOUD_VOL_STEPS = new int[] { 0, 10, 18, 32 }[OPT_CLOUD_QUALITY];
        OPT_CLOUD_OPACITY_SCALE = 1.0f;
        OPT_CLOUD_SHADOW_SCALE = 1.0f;
        // Profiling runs need to be repeatable, and stopping at the title screen
        // to click through to a world makes that awkward.
        if (flag("delve.autoplay")) {
            long seed = Long.getLong("delve.seed", new java.util.Random().nextLong());
            INSTANCE.startWorld(SaveGame.create(SaveGame.nextDefaultName(), seed, 0));
        }
    }

    /**
     * Loads or creates a world and switches to gameplay.
     */
    public void startWorld(SaveGame save) {
        CURRENT_SAVE = save;
        World.reset(save.seed, save.worldPreset);
        applyWorldPresetPhysics(save.worldPreset);

        GAME_WORLD = new World(PLAYER_START_POSITION);
        GAME_CAMERA = GAME_WORLD.camera;
        GAME_WORLD.loadModels();
        GAME_WORLD.loadTextures();

        TIME_OF_DAY = save.timeOfDay;
        DAY_COUNT = save.dayCount;
        DAYS_WITH_WEATHER_CYCLES = true;
        Weather.reset(DAY_COUNT + TIME_OF_DAY);

        if (save.hasPlayerPosition) {
            GAME_CAMERA.position.x = save.playerX;
            GAME_CAMERA.position.y = save.playerY;
            GAME_CAMERA.position.z = save.playerZ;
            GAME_CAMERA.setOrientation(save.yaw, save.pitch);
        } else {
            GAME_CAMERA.position.y = World.getHeightAt(
                    (int) Math.floor(GAME_CAMERA.position.x),
                    (int) Math.floor(GAME_CAMERA.position.z))
                    + OPT_CAMERA_DISTANCE_FROM_BLOCKS[1] + Block.size * 2;
        }

        setupPerspective();
        updateCrosshair();

        SCREEN = Screen.PLAYING;
        MENU_OPEN = false;
        DEV_MENU_OPEN = false;
        WINDOW.setCursorGrabbed(true);
        INPUT.resetLook();
        System.out.println("Playing world '" + save.name + "' (seed " + save.seed
                + ", preset " + WorldPreset.nameOf(save.worldPreset) + ")");
    }

    private static void applyWorldPresetPhysics(int preset) {
        int p = WorldPreset.clamp(preset);
        PLAYER_BASE_MOVEMENT_SPEED = WorldPreset.baseMoveSpeed(p);
        PLAYER_FLY_SPEED_BONUS = WorldPreset.flySpeedBonus(p);
        PLAYER_MOVEMENT_SPEED = PLAYER_BASE_MOVEMENT_SPEED + (GAME_FLYMODE ? PLAYER_FLY_SPEED_BONUS : 0f);
        PLAYER_JUMP_FORCE = WorldPreset.jumpForce(p);
        Camera.CAMERA_GRAVITY = WorldPreset.gravity(p);
        Camera.CAMERA_DRAG = WorldPreset.drag(p);
    }

    /** Writes the world and returns to the title screen. */
    public void saveAndExit() {
        saveWorld();
        World.shutdown();
        SCREEN = Screen.TITLE;
        MENU_OPEN = false;
        DEV_MENU_OPEN = false;
        CURRENT_SAVE = null;
        GAME_WORLD = null;
        WINDOW.setCursorGrabbed(false);
        TITLE.openMain();
    }

    /** Persists player state and every modified chunk still in memory. */
    public void saveWorld() {
        if (CURRENT_SAVE == null || GAME_CAMERA == null) {
            return;
        }
        CURRENT_SAVE.playerX = GAME_CAMERA.position.x;
        CURRENT_SAVE.playerY = GAME_CAMERA.position.y;
        CURRENT_SAVE.playerZ = GAME_CAMERA.position.z;
        CURRENT_SAVE.yaw = GAME_CAMERA.getYaw();
        CURRENT_SAVE.pitch = GAME_CAMERA.getPitch();
        CURRENT_SAVE.timeOfDay = TIME_OF_DAY;
        CURRENT_SAVE.dayCount = DAY_COUNT;
        CURRENT_SAVE.hasPlayerPosition = true;
        CURRENT_SAVE.write();

        int saved = 0;
        for (WorldChunk chunk : new java.util.ArrayList<>(World.chunks)) {
            if (chunk != null && chunk.isModified && chunk.isGenerated) {
                chunk.save();
                saved++;
            }
        }
        System.out.println("Saved world '" + CURRENT_SAVE.name + "' (" + saved + " chunks)");
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
                    FrameStats.beginFrame();
                    int delta = getDelta();
                    GAME_TIME = delta;

                    FrameStats.beginUpdate();
                    update(delta);
                    FrameStats.endUpdate();

                    FrameStats.beginRender();
                    render();
                    FrameStats.endRender();
                    FrameCapture.captureMaybe();

                    WINDOW.swapBuffers();
                    FrameStats.endFrame();
                    if (OPT_PERF_OVERLAY == PerfOverlay.FULL) {
                        ShaderProfiler.update();
                    }
                    PerfLog.update();
                }
            }
            // Closing the window mid-game should not discard progress.
            if (SCREEN == Screen.PLAYING) {
                saveWorld();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void update(long gameTime) {
        updateFPS();
        INPUT.update(gameTime);

        if (SCREEN != Screen.PLAYING) {
            Renderer.updateSky(TIME_OF_DAY, DAY_COUNT);
            return;
        }

        // Snapshot last frame's totals before zeroing, so the HUD reports real
        // numbers instead of the counters it is about to reset.
        LAST_FACE_COUNT = FACE_COUNT;
        LAST_BLOCK_COUNT = BLOCK_COUNT;
        LAST_VBO_CHUNKS = World.VBO_CHUNKS;

        FACE_COUNT = 0;
        BLOCK_COUNT = 0;
        if (!TIME_PAUSED && !MENU_OPEN && !DEV_MENU_OPEN) {
            float delta = (gameTime / 1000.0f) * TIME_SPEED / dayLengthSeconds();
            float advanced = TIME_OF_DAY + delta;
            if (advanced >= 1.0f) {
                DAY_COUNT += (int) advanced;
            }
            TIME_OF_DAY = advanced % 1.0f;
            Weather.update(DAY_COUNT + TIME_OF_DAY);
        }
        Renderer.updateSky(TIME_OF_DAY, DAY_COUNT);
        if (!MENU_OPEN && !DEV_MENU_OPEN) {
            GAME_CAMERA.update();
            GAME_WORLD.update();
        }
    }

    public void switchMode() {
        APP_FULLSCREEN = !APP_FULLSCREEN;
        WINDOW.toggleFullscreen();
        setupPerspective();
        updateCrosshair();
    }

    /** Opens or closes the settings overlay, releasing the cursor while open. */
    public static void setMenuOpen(boolean open) {
        MENU_OPEN = open;
        if (WINDOW != null) {
            WINDOW.setCursorGrabbed(!open);
        }
    }

    /** Opens or closes the hidden developer tuning overlay (F9). */
    public static void setDevMenuOpen(boolean open) {
        DEV_MENU_OPEN = open;
        if (WINDOW != null) {
            WINDOW.setCursorGrabbed(!open);
        }
    }

    private void render() {
        GpuProfiler.beginFrame();
        if (SCREEN != Screen.PLAYING) {
            Renderer.clear();
            TITLE.render();
            GpuProfiler.endFrame();
            return;
        }

        GAME_CAMERA.lookThrough();
        Renderer.view().set(Camera.getViewMatrix());
        Renderer.projection().set(Camera.getProjectionMatrix());

        // The world is rendered offscreen so god rays can sample depth.
        Renderer.beginScene(WINDOW.getWidth(), WINDOW.getHeight());
        Renderer.drawCelestialBodies();
        GAME_WORLD.render();
        Renderer.endScene(WINDOW.getWidth(), WINDOW.getHeight());

        GpuProfiler.begin(GpuProfiler.Zone.HUD);
        GUI.render();
        drawCrosshairs();

        if (MENU_OPEN) {
            MENU.render();
        }
        if (DEV_MENU_OPEN) {
            DEV_MENU.render();
        }
        PerfOverlay.render();
        GpuProfiler.end(GpuProfiler.Zone.HUD);
        GpuProfiler.endFrame();
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
                  +/- x2    double time speed        /-   0.1x - half speed
                  F4/F5        draw distance -/+   F7 frustum culling
  F6           performance overlay (off / frame times / shader profile)
                  F8           vsync               F11 fullscreen
                  Esc          settings menu       F9 developer tuning menu
                  L            flashlight
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
            String biomeLabel = "";
            if (GAME_CAMERA != null) {
                int wx = (int) Math.floor(GAME_CAMERA.position.x);
                int wz = (int) Math.floor(GAME_CAMERA.position.z);
                biomeLabel = " | biome: " + WorldChunk.biomeLabelAt(wx, wz);
            }
            // Time speed as power of 2: 1x, 2x, 4x, 8x, 16x...
            String speedStr;
            if (Game.TIME_SPEED < 1.5f) {
                speedStr = "1x";
            } else if (Game.TIME_SPEED < 3.0f) {
                speedStr = "2x";
            } else if (Game.TIME_SPEED < 6.0f) {
                speedStr = "4x";
            } else if (Game.TIME_SPEED < 12.0f) {
                speedStr = "8x";
            } else if (Game.TIME_SPEED < 24.0f) {
                speedStr = "16x";
            } else if (Game.TIME_SPEED < 48.0f) {
                speedStr = "32x";
            } else {
                speedStr = String.format("%.1fx", Game.TIME_SPEED);
            }
            WINDOW.setTitle(APP_WINDOW_TITLE + " | FPS: " + FRAMES_PER_SECOND
                    + " | block: " + Block.nameOf(SELECTED_BLOCK_TYPE)
                    + " | Time Speed: " + speedStr
                    + " | " + Weather.condition.label
                    + biomeLabel
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
        World.shutdown();
        Renderer.cleanup();
        if (WINDOW != null) {
            WINDOW.destroy();
        }
    }
}
