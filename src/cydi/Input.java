/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Input handling built on GLFW.
 *
 * LWJGL 3 removed org.lwjgl.input.Keyboard/Mouse, so held-key state is polled via
 * glfwGetKey and one-shot actions are edge-detected against the previous frame's
 * state. Mouse look uses relative cursor deltas captured from the cursor callback.
 */
public class Input {

    private final Game game;
    private final Window window;

    private static final int MAX_KEYS = GLFW_KEY_LAST + 1;
    /**
     * Keys polled for edge-triggered actions. Only these are seeded, because
     * glfwGetKey rejects unassigned codes in the 0..GLFW_KEY_LAST range.
     */
    private static final int[] TRACKED_KEYS = {
        GLFW_KEY_SPACE, GLFW_KEY_G, GLFW_KEY_ESCAPE, GLFW_KEY_F, GLFW_KEY_C,
        GLFW_KEY_B, GLFW_KEY_R, GLFW_KEY_T, GLFW_KEY_F3, GLFW_KEY_F4,
        GLFW_KEY_F5, GLFW_KEY_F7, GLFW_KEY_F8, GLFW_KEY_F11,
        GLFW_KEY_L, GLFW_KEY_P,
    };

    private final boolean[] previousKeys = new boolean[MAX_KEYS];
    private boolean keyStateSeeded;

    private double lastCursorX;
    private double lastCursorY;
    private double cursorDeltaX;
    private double cursorDeltaY;
    private boolean firstCursorSample = true;

    private boolean leftMouseWasDown;
    private boolean rightMouseWasDown;

    private long doubleTapWindowEnd;

    public Input(Game game, Window window) {
        this.game = game;
        this.window = window;

        glfwSetCursorPosCallback(window.handle(), (win, x, y) -> {
            if (firstCursorSample) {
                lastCursorX = x;
                lastCursorY = y;
                firstCursorSample = false;
                return;
            }
            cursorDeltaX += x - lastCursorX;
            cursorDeltaY += y - lastCursorY;
            lastCursorX = x;
            lastCursorY = y;
        });

        // Mouse wheel cycles the block the player will place.
        glfwSetScrollCallback(window.handle(), (win, dx, dy) -> {
            if (dy != 0) {
                cycleBlock(dy > 0 ? 1 : -1);
            }
        });
    }

    private void cycleBlock(int direction) {
        int[] types = Block.PLACEABLE_TYPES;
        int index = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Game.SELECTED_BLOCK_TYPE) {
                index = i;
                break;
            }
        }
        index = Math.floorMod(index + direction, types.length);
        Game.SELECTED_BLOCK_TYPE = types[index];
        Game.consoleMsg("Selected " + Block.nameOf(Game.SELECTED_BLOCK_TYPE));
    }

    private boolean isDown(int key) {
        return glfwGetKey(window.handle(), key) == GLFW_PRESS;
    }

    /** True only on the frame the key transitions from up to down. */
    private boolean wasPressed(int key) {
        boolean down = isDown(key);
        boolean pressed = down && !previousKeys[key];
        previousKeys[key] = down;
        return pressed;
    }

    public void update(long gameTime) {
        // Seed key state on the first frame. Sampling edges against an all-false
        // array would report a spurious press for anything already held at startup.
        if (!keyStateSeeded) {
            for (int k : TRACKED_KEYS) {
                previousKeys[k] = glfwGetKey(window.handle(), k) == GLFW_PRESS;
            }
            keyStateSeeded = true;
            cursorDeltaX = 0;
            cursorDeltaY = 0;
            return;
        }

        float dt = gameTime / 1000.0f;

        if (Game.MENU_OPEN) {
            updateMenu();
            // Discard look deltas so the camera does not spin while the cursor is
            // free, and skip all world interaction.
            cursorDeltaX = 0;
            cursorDeltaY = 0;
            handleMenuToggle();
            return;
        }

        // Mouse look
        Game.GAME_CAMERA.yaw((float) cursorDeltaX * Game.PLAYER_MOUSE_SENSITIVITY);
        Game.GAME_CAMERA.pitch((float) cursorDeltaY * Game.PLAYER_MOUSE_SENSITIVITY);
        cursorDeltaX = 0;
        cursorDeltaY = 0;

        handleMovement(dt);
        handleMouseButtons();
        handleToggles();
    }

    private void updateMenu() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.DoubleBuffer mx = stack.mallocDouble(1);
            java.nio.DoubleBuffer my = stack.mallocDouble(1);
            glfwGetCursorPos(window.handle(), mx, my);
            Game.MENU.updateHover(mx.get(0), my.get(0));

            boolean left = glfwGetMouseButton(window.handle(), GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
            boolean right = glfwGetMouseButton(window.handle(), GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;
            if (left && !leftMouseWasDown) {
                Game.MENU.click(mx.get(0), my.get(0), false);
            }
            if (right && !rightMouseWasDown) {
                Game.MENU.click(mx.get(0), my.get(0), true);
            }
            leftMouseWasDown = left;
            rightMouseWasDown = right;
        }
    }

    private void handleMenuToggle() {
        if (wasPressed(GLFW_KEY_ESCAPE)) {
            Game.setMenuOpen(false);
            firstCursorSample = true;   // resync so the view does not jump
        }
    }

    private void handleMovement(float dt) {
        float step = Game.PLAYER_MOVEMENT_SPEED * dt;

        if (isDown(GLFW_KEY_W)) {
            Game.GAME_CAMERA.walkForward(step);
        }
        if (isDown(GLFW_KEY_S)) {
            Game.GAME_CAMERA.walkBackwards(step);
        }
        if (isDown(GLFW_KEY_A)) {
            Game.GAME_CAMERA.strafeLeft(step);
        }
        if (isDown(GLFW_KEY_D)) {
            Game.GAME_CAMERA.strafeRight(step);
        }
        if (isDown(GLFW_KEY_LEFT_SHIFT)) {
            Game.GAME_CAMERA.fallDown(step * 2.0f);
            if (Game.GAME_CAMERA.onGround) {
                Game.GAME_FLYMODE = false;
                Game.PLAYER_MOVEMENT_SPEED = 2.85f;
            }
        }
        if (isDown(GLFW_KEY_SPACE) && Game.GAME_FLYMODE) {
            Game.GAME_CAMERA.flyUp(step);
        }

        if (isDown(GLFW_KEY_EQUAL)) {
            Game.PLAYER_MOVEMENT_SPEED = Math.min(100.0f, Game.PLAYER_MOVEMENT_SPEED + 5.0f * dt);
        }
        if (isDown(GLFW_KEY_MINUS)) {
            Game.PLAYER_MOVEMENT_SPEED = Math.max(0.0f, Game.PLAYER_MOVEMENT_SPEED - 5.0f * dt);
        }

        // Double-tap space toggles fly mode, single tap jumps.
        if (wasPressed(GLFW_KEY_SPACE)) {
            long now = System.currentTimeMillis();
            if (now < doubleTapWindowEnd) {
                setFlyMode(!Game.GAME_FLYMODE);
                doubleTapWindowEnd = 0;
            } else {
                doubleTapWindowEnd = now + 300;
                if (!Game.GAME_FLYMODE) {
                    Game.GAME_CAMERA.velocity.y += Game.PLAYER_JUMP_FORCE;
                }
            }
        }

        // Dedicated toggle, since double-tapping is easy to miss.
        if (wasPressed(GLFW_KEY_G)) {
            setFlyMode(!Game.GAME_FLYMODE);
        }
    }

    private void setFlyMode(boolean enabled) {
        if (Game.GAME_FLYMODE == enabled) {
            return;
        }
        Game.GAME_FLYMODE = enabled;
        Game.PLAYER_MOVEMENT_SPEED += enabled ? 2 : -2;
        Game.consoleMsg("Fly mode " + (enabled ? "ON" : "OFF"));
        System.out.println("Fly mode " + (enabled ? "ON" : "OFF"));
    }

    private void handleMouseButtons() {
        boolean left = glfwGetMouseButton(window.handle(), GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean right = glfwGetMouseButton(window.handle(), GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        if (left && !leftMouseWasDown) {
            World.BREAK_BLOCK_REQUESTED = true;
        }
        if (right && !rightMouseWasDown) {
            World.PLACE_BLOCK_REQUESTED = true;
        }
        leftMouseWasDown = left;
        rightMouseWasDown = right;
    }

    private void handleToggles() {
        if (wasPressed(GLFW_KEY_ESCAPE)) {
            Game.setMenuOpen(true);
        }
        if (wasPressed(GLFW_KEY_F)) {
            Game.OPT_FOG = !Game.OPT_FOG;
        }
        if (wasPressed(GLFW_KEY_C)) {
            Game.OPT_BLOCK_COLLISION = !Game.OPT_BLOCK_COLLISION;
        }
        if (wasPressed(GLFW_KEY_B)) {
            Game.OPT_DRAW_COLORED_BLOCKS = !Game.OPT_DRAW_COLORED_BLOCKS;
        }
        if (wasPressed(GLFW_KEY_R)) {
            Game.DEBUG_DRAW_CAMERA_RAY = !Game.DEBUG_DRAW_CAMERA_RAY;
        }
        if (wasPressed(GLFW_KEY_T)) {
            if (Game.OPT_USE_TEXTURES && Game.OPT_DRAW_TEXTURES) {
                Game.OPT_DRAW_TEXTURES = false;
            } else if (Game.OPT_USE_TEXTURES ^ Game.OPT_DRAW_TEXTURES) {
                Game.OPT_USE_TEXTURES = !Game.OPT_USE_TEXTURES;
            } else {
                Game.OPT_USE_TEXTURES = true;
                Game.OPT_DRAW_TEXTURES = true;
            }
        }
        if (wasPressed(GLFW_KEY_F3)) {
            Game.OPT_DRAW_WIRES = !Game.OPT_DRAW_WIRES;
            Renderer.setWireframe(Game.OPT_DRAW_WIRES);
        }
        if (wasPressed(GLFW_KEY_F4) && Game.OPT_DRAW_DISTANCE > Game.OPT_MIN_DRAW_DISTANCE) {
            Game.OPT_DRAW_DISTANCE -= 1;
            game.setupPerspective();
        }
        if (wasPressed(GLFW_KEY_F5) && Game.OPT_DRAW_DISTANCE < Game.OPT_MAX_DRAW_DISTANCE && !Game.MEMORY_BOUND) {
            Game.OPT_DRAW_DISTANCE += 1;
            game.setupPerspective();
        }
        if (wasPressed(GLFW_KEY_F7)) {
            Game.FRUSTUM_CULLING = !Game.FRUSTUM_CULLING;
        }
        if (wasPressed(GLFW_KEY_L)) {
            Game.OPT_FLASHLIGHT = !Game.OPT_FLASHLIGHT;
        }
        if (wasPressed(GLFW_KEY_P)) {
            Game.TIME_PAUSED = !Game.TIME_PAUSED;
        }
        if (isDown(GLFW_KEY_LEFT_BRACKET)) {
            Game.TIME_OF_DAY = (Game.TIME_OF_DAY + 0.995f) % 1.0f;
        }
        if (isDown(GLFW_KEY_RIGHT_BRACKET)) {
            Game.TIME_OF_DAY = (Game.TIME_OF_DAY + 0.005f) % 1.0f;
        }
        if (wasPressed(GLFW_KEY_F8)) {
            Game.OPT_VSYNC = !Game.OPT_VSYNC;
            window.setVSync(Game.OPT_VSYNC);
        }
        if (wasPressed(GLFW_KEY_F11)) {
            game.switchMode();
        }
    }
}
