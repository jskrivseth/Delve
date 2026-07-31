package cydi;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Modern OpenGL core-profile renderer.
 *
 * Replaces the fixed-function pipeline entirely: matrices come from JOML and are
 * uploaded as uniforms, lighting and fog are computed in the fragment shader, and
 * geometry is submitted through VAOs rather than glVertexPointer/client state.
 */
public class Renderer {

    /** position(3) + normal(3) + color+ao(4) + texcoord(2) + skylight(1) */
    public static final int FLOATS_PER_VERTEX = 13;
    public static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private static ShaderProgram chunkShader;
    private static ShaderProgram lineShader;
    private static ShaderProgram hudShader;
    private static ShaderProgram skyShader;
    private static ShaderProgram skyGradientShader;
    private static ShaderProgram godRayShader;
    private static ShaderProgram compositeShader;
    private static int skyVao;
    private static int skyVbo;
    private static int fullscreenVao;
    private static int fullscreenVbo;
    private static Framebuffer sceneBuffer;
    private static Framebuffer raysBuffer;
    private static final org.joml.Matrix4f invViewProjection = new org.joml.Matrix4f();
    private static final org.joml.Vector4f screenPosScratch = new org.joml.Vector4f();

    private static final Matrix4f projection = new Matrix4f();
    private static final Matrix4f view = new Matrix4f();
    private static final Matrix4f modelScratch = new Matrix4f();
    private static final Matrix4f viewRotationScratch = new Matrix4f();

    private static int lineVao;
    private static int lineVbo;
    private static int lineCapacityFloats;
    private static FloatBuffer lineStaging;

    private static int hudVao;
    private static int hudVbo;
    private static int hudCapacityFloats;
    private static FloatBuffer hudStaging;

    private static Texture terrainTexture;
    private static Texture sunTexture;
    private static Texture moonTexture;
    private static java.util.List<TexturePack> texturePacks = new java.util.ArrayList<>();
    private static int texturePackIndex = 0;
    private static boolean wireframe;

    /** Chunks holding translucent geometry, drawn after all opaque chunks. */
    private static final java.util.ArrayList<WorldChunk> translucentQueue = new java.util.ArrayList<>();

    /** Sky and sun state for the current time of day. */
    private static float skyR = 0.52f, skyG = 0.80f, skyB = 0.92f;
    private static float fogR = 0.70f, fogG = 0.82f, fogB = 0.95f;
    private static float fogDensity = 0.015f;
    private static float fogHeightFalloff = 0.040f;
    private static float fogBaseHeight = 30.0f;
    private static float fogNoiseScale = 0.018f;
    private static float fogTimeScale = 0.045f;
    private static float fogValleyStrength = 0.26f;
    private static float fogValleyTop = 30.0f;
    private static float sunDirX = -0.35f, sunDirY = -1.0f, sunDirZ = -0.45f;
    private static float sunR = 0.68f, sunG = 0.64f, sunB = 0.55f;
    private static float sunDiscR = 1.0f, sunDiscG = 0.95f, sunDiscB = 0.82f;
    private static float moonDirX = 0.35f, moonDirY = 1.0f, moonDirZ = 0.45f;
    private static float marsMoonADirX = 0.15f, marsMoonADirY = 1.0f, marsMoonADirZ = 0.25f;
    private static float marsMoonBDirX = -0.25f, marsMoonBDirY = 1.0f, marsMoonBDirZ = -0.35f;
    private static float neptuneDirX = 0.40f, neptuneDirY = 1.0f, neptuneDirZ = -0.55f;
    private static float moonR = 0f, moonG = 0f, moonB = 0f;
    private static float ambR = 0.40f, ambG = 0.45f, ambB = 0.55f;
    private static float groundR = 0.22f, groundG = 0.20f, groundB = 0.17f;
    private static float sunElevation = 1f;
    private static float moonElevation = -1f;
    private static float moonIllumination = 1f;
    private static float moonPhaseAngle = (float) Math.PI;
    private static int moonPhase = 0;
    private static float dayFactor = 1f;
    private static float duskFactor = 0f;
    private static float moonGlowFactor = 0f;
    private static boolean cloudsEnabled = true;
    private static float cloudCoverage = 0.52f;
    private static float cloudSharpness = 0.11f;
    private static float cloudOpacity = 0.55f;
    private static float cloudShadowStrength = 0.42f;
    private static float cloudBaseHeight = 92.0f;
    private static float cloudLayerDepth = 34.0f;
    private static float cloudSpeed = 0.55f;
    private static float sunCloudOcclusion = 1.0f;

    /** Names for the eight moon phases, indexed by {@link #getMoonPhase()}. */
    public static final String[] MOON_PHASES = {
        "New", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
        "Full", "Waning Gibbous", "Last Quarter", "Waning Crescent",
    };

    public static int getMoonPhase() {
        return moonPhase;
    }

    public static float getSunElevation() {
        return sunElevation;
    }

    /**
     * Shader owning the current pass. Debug geometry is drawn in the middle of the
     * chunk pass, so it must restore this instead of unbinding, or every chunk
     * drawn afterwards would render with no program bound.
     */
    private static ShaderProgram activePass;

    public static void init() {
        chunkShader = new ShaderProgram("/shaders/chunk.vert", "/shaders/chunk.frag");
        lineShader = new ShaderProgram("/shaders/line.vert", "/shaders/line.frag");
        hudShader = new ShaderProgram("/shaders/hud.vert", "/shaders/hud.frag");
        skyShader = new ShaderProgram("/shaders/sky.vert", "/shaders/sky.frag");
        skyGradientShader = new ShaderProgram("/shaders/skygradient.vert", "/shaders/skygradient.frag");
        godRayShader = new ShaderProgram("/shaders/post.vert", "/shaders/godrays.frag");
        compositeShader = new ShaderProgram("/shaders/post.vert", "/shaders/composite.frag");

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // The framebuffer is always created with samples, so antialiasing can be
        // toggled at runtime without recreating the window and GL context.
        glEnable(GL_MULTISAMPLE);
        glClearColor(0.52f, 0.80f, 0.92f, 1.0f);

        lineVao = glGenVertexArrays();
        lineVbo = glGenBuffers();
        glBindVertexArray(lineVao);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glBindVertexArray(0);
        ensureLineCapacity(1024);

        // HUD quads: vec2 position (NDC) + vec2 atlas texcoord.
        hudVao = glGenVertexArrays();
        hudVbo = glGenBuffers();
        glBindVertexArray(hudVao);
        glBindBuffer(GL_ARRAY_BUFFER, hudVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glBindVertexArray(0);
        ensureHudCapacity(2048);

        // Unit quad reused for both celestial bodies.
        float[] quad = {-1, -1, -1, 1, 1, 1, 1, 1, 1, -1, -1, -1};
        skyVao = glGenVertexArrays();
        skyVbo = glGenBuffers();
        glBindVertexArray(skyVao);
        glBindBuffer(GL_ARRAY_BUFFER, skyVbo);
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Fullscreen triangle for the sky gradient and post passes.
        float[] fullscreen = {-1, -1, 3, -1, -1, 3};
        fullscreenVao = glGenVertexArrays();
        fullscreenVbo = glGenBuffers();
        glBindVertexArray(fullscreenVao);
        glBindBuffer(GL_ARRAY_BUFFER, fullscreenVbo);
        glBufferData(GL_ARRAY_BUFFER, fullscreen, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        terrainTexture = Texture.loadOrNull("media/art/terrain.png");
        if (terrainTexture == null) {
            Game.OPT_USE_TEXTURES = false;
            System.out.println("No terrain texture found; falling back to vertex colors.");
        }

        texturePacks = TexturePack.discover();
        applyTexturePack(0);
    }

    public static java.util.List<TexturePack> getTexturePacks() {
        return texturePacks;
    }

    public static String getTexturePackName() {
        if (texturePacks.isEmpty()) {
            return "Default";
        }
        return texturePacks.get(texturePackIndex).getName();
    }

    public static void deleteChunkMesh(WorldChunk chunk) {
        if (chunk.vboVertexHandle != 0) {
            glDeleteBuffers(chunk.vboVertexHandle);
            chunk.vboVertexHandle = 0;
        }
        if (chunk.vaoHandle != 0) {
            glDeleteVertexArrays(chunk.vaoHandle);
            chunk.vaoHandle = 0;
        }
    }

    /** Rescans the pack folder, keeping the current selection if it survives. */
    public static void rescanTexturePacks() {
        String current = getTexturePackName();
        texturePacks = TexturePack.discover();
        for (int i = 0; i < texturePacks.size(); i++) {
            if (texturePacks.get(i).getName().equals(current)) {
                texturePackIndex = i;
                return;
            }
        }
        applyTexturePack(0);
    }

    public static void cycleTexturePack(int direction) {
        if (texturePacks.size() <= 1) {
            rescanTexturePacks();
        }
        if (texturePacks.isEmpty()) {
            return;
        }
        applyTexturePack(Math.floorMod(texturePackIndex + direction, texturePacks.size()));
    }

    /**
     * Swaps every texture to the given pack.
     *
     * Block UVs are baked against fixed atlas slots, so only the GL textures
     * change; no chunk needs to be re-meshed.
     */
    private static void applyTexturePack(int index) {
        if (texturePacks.isEmpty()) {
            return;
        }
        texturePackIndex = Math.floorMod(index, texturePacks.size());
        TexturePack pack = texturePacks.get(texturePackIndex);

        java.awt.image.BufferedImage atlas = pack.loadTerrainAtlas();
        if (atlas != null) {
            if (terrainTexture != null) {
                terrainTexture.cleanup();
            }
            terrainTexture = Texture.fromImage(atlas, true);
            Game.OPT_USE_TEXTURES = true;
        }

        java.awt.image.BufferedImage sun = pack.loadSun();
        if (sun != null) {
            if (sunTexture != null) {
                sunTexture.cleanup();
            }
            sunTexture = Texture.fromImage(sun, false);
        }

        java.awt.image.BufferedImage moon = pack.loadMoonPhases();
        if (moon != null) {
            if (moonTexture != null) {
                moonTexture.cleanup();
            }
            moonTexture = Texture.fromImage(moon, false);
        }

        System.out.println("Texture pack: " + pack.getName());
    }

    public static void setAntialiasing(boolean enabled) {
        if (enabled) {
            glEnable(GL_MULTISAMPLE);
        } else {
            glDisable(GL_MULTISAMPLE);
        }
    }

    /**
     * Binds the offscreen target and paints the sky gradient.
     *
     * The scene is rendered offscreen so the god ray pass can read depth and
     * know where terrain occludes the sky.
     */
    public static void beginScene(int screenWidth, int screenHeight) {
        if (sceneBuffer == null) {
            sceneBuffer = new Framebuffer(screenWidth, screenHeight);
            raysBuffer = new Framebuffer(Math.max(1, screenWidth / 2), Math.max(1, screenHeight / 2));
        } else {
            sceneBuffer.resize(screenWidth, screenHeight);
            raysBuffer.resize(Math.max(1, screenWidth / 2), Math.max(1, screenHeight / 2));
        }

        sceneBuffer.bind();
        glClearColor(skyR, skyG, skyB, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        drawSkyGradient();
    }

    /** Paints the atmospheric gradient over the whole framebuffer. */
    private static void drawSkyGradient() {
        projection.mul(view, invViewProjection);
        invViewProjection.invert();

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        skyGradientShader.bind();
        skyGradientShader.setMatrix4f("invViewProjection", invViewProjection);
        skyGradientShader.setVector3f("sunDirection", sunDirX, sunDirY, sunDirZ);
        skyGradientShader.setVector3f("moonDirection", moonDirX, moonDirY, moonDirZ);
        skyGradientShader.setFloat("dayFactor", dayFactor);
        skyGradientShader.setFloat("duskFactor", duskFactor);
        skyGradientShader.setFloat("moonGlow", moonGlowFactor);
        skyGradientShader.setInt("atmospherePreset", WorldPreset.clamp(World.WORLD_PRESET));
        float camX = 0.0f;
        float camY = 64.0f;
        float camZ = 0.0f;
        if (Game.GAME_CAMERA != null && Game.GAME_CAMERA.position != null) {
            camX = (float) Game.GAME_CAMERA.position.x;
            camY = (float) Game.GAME_CAMERA.position.y;
            camZ = (float) Game.GAME_CAMERA.position.z;
        }
        skyGradientShader.setVector3f("cameraWorldPos", camX, camY, camZ);
        skyGradientShader.setBoolean("cloudsEnabled", cloudsEnabled && Game.OPT_FOG);
        skyGradientShader.setFloat("cloudCoverage", cloudCoverage);
        skyGradientShader.setFloat("cloudSharpness", cloudSharpness);
        skyGradientShader.setFloat("cloudOpacity", cloudOpacity);
        skyGradientShader.setFloat("cloudBaseHeight", cloudBaseHeight);
        skyGradientShader.setFloat("cloudLayerDepth", cloudLayerDepth);
        skyGradientShader.setFloat("cloudTime", (float) org.lwjgl.glfw.GLFW.glfwGetTime());
        skyGradientShader.setFloat("cloudSpeed", cloudSpeed);
        skyGradientShader.setFloat("cloudDayTime", Game.DAY_COUNT + Game.TIME_OF_DAY);

        glBindVertexArray(fullscreenVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);

        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        ShaderProgram.unbind();
    }

    /**
     * Resolves the offscreen scene to the window, adding god rays.
     */
    public static void endScene(int screenWidth, int screenHeight) {
        if (sceneBuffer == null) {
            return;
        }

        boolean drewRays = Game.OPT_GOD_RAYS && renderGodRays();

        Framebuffer.unbind(screenWidth, screenHeight);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        compositeShader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneBuffer.getColorTexture());
        compositeShader.setInt("sceneTexture", 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, raysBuffer.getColorTexture());
        compositeShader.setInt("raysTexture", 1);
        compositeShader.setBoolean("raysEnabled", drewRays);

        glBindVertexArray(fullscreenVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);

        glActiveTexture(GL_TEXTURE0);
        glEnable(GL_CULL_FACE);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        ShaderProgram.unbind();
    }

    /**
     * Renders light shafts for whichever body is above the horizon.
     *
     * @return true when shafts were produced
     */
    private static boolean renderGodRays() {
        // Pick the dominant light. Both can be up, but only the brighter one
        // casts shafts worth the cost.
        boolean useSun = -sunDirY > -moonDirY;
        float dirX = useSun ? -sunDirX : -moonDirX;
        float dirY = useSun ? -sunDirY : -moonDirY;
        float dirZ = useSun ? -sunDirZ : -moonDirZ;

        if (dirY <= 0.01f) {
            return false;   // below the horizon, so the earth blocks the shafts
        }

        // Project the light onto the screen using a rotation-only view, since it
        // is treated as infinitely distant.
        viewRotationScratch.set(view);
        viewRotationScratch.m30(0f);
        viewRotationScratch.m31(0f);
        viewRotationScratch.m32(0f);
        screenPosScratch.set(dirX, dirY, dirZ, 1.0f);
        viewRotationScratch.transform(screenPosScratch);
        projection.transform(screenPosScratch);

        if (screenPosScratch.w <= 0.0f) {
            return false;   // behind the camera
        }
        float sx = (screenPosScratch.x / screenPosScratch.w) * 0.5f + 0.5f;
        float sy = (screenPosScratch.y / screenPosScratch.w) * 0.5f + 0.5f;

        // Fade the effect out as the light leaves the view, so shafts do not pop.
        float offCentre = Math.max(Math.abs(sx - 0.5f), Math.abs(sy - 0.5f));
        float edgeFade = clamp01(1.6f - offCentre * 2.2f);
        if (edgeFade <= 0.01f) {
            return false;
        }

        float strength = useSun
                ? (0.55f + 0.45f * duskFactor) * clamp01(dirY * 3.0f)
                : 0.30f * moonIllumination * clamp01(dirY * 3.0f);
        strength *= edgeFade;
        if (strength <= 0.01f) {
            return false;
        }

        raysBuffer.bind();
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        godRayShader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneBuffer.getColorTexture());
        godRayShader.setInt("sceneTexture", 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, sceneBuffer.getDepthTexture());
        godRayShader.setInt("depthTexture", 1);

        godRayShader.setVector4f("lightScreenPos", sx, sy, 0f, 0f);
        godRayShader.setFloat("intensity", strength);
        godRayShader.setFloat("decay", 0.972f);
        godRayShader.setFloat("density", 0.85f);
        projection.mul(view, invViewProjection);
        invViewProjection.invert();
        godRayShader.setMatrix4f("invViewProjection", invViewProjection);
        float camX = 0.0f;
        float camY = 64.0f;
        float camZ = 0.0f;
        if (Game.GAME_CAMERA != null && Game.GAME_CAMERA.position != null) {
            camX = (float) Game.GAME_CAMERA.position.x;
            camY = (float) Game.GAME_CAMERA.position.y;
            camZ = (float) Game.GAME_CAMERA.position.z;
        }
        int preset = WorldPreset.clamp(World.WORLD_PRESET);
        godRayShader.setVector3f("cameraWorldPos", camX, camY, camZ);
        godRayShader.setBoolean("cloudsEnabled", cloudsEnabled && useSun);
        godRayShader.setFloat("cloudCoverage", cloudCoverage);
        godRayShader.setFloat("cloudSharpness", cloudSharpness);
        godRayShader.setFloat("cloudOpacity", cloudOpacity);
        godRayShader.setFloat("cloudBaseHeight", cloudBaseHeight);
        godRayShader.setFloat("cloudLayerDepth", cloudLayerDepth);
        godRayShader.setFloat("cloudTime", (float) org.lwjgl.glfw.GLFW.glfwGetTime());
        godRayShader.setFloat("cloudSpeed", cloudSpeed);
        godRayShader.setFloat("cloudDayTime", Game.DAY_COUNT + Game.TIME_OF_DAY);
        godRayShader.setInt("atmospherePreset", preset);
        if (useSun) {
            godRayShader.setVector3f("lightColor", sunDiscR, sunDiscG * 0.92f, sunDiscB * 0.80f);
        } else {
            godRayShader.setVector3f("lightColor", 0.62f, 0.70f, 0.95f);
        }

        glBindVertexArray(fullscreenVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);

        glActiveTexture(GL_TEXTURE0);
        glEnable(GL_CULL_FACE);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        ShaderProgram.unbind();
        return true;
    }

    public static void clear() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    public static Matrix4f projection() {
        return projection;
    }

    public static Matrix4f view() {
        return view;
    }

    public static void setWireframe(boolean enabled) {
        wireframe = enabled;
    }

    /**
     * Creates/updates the VAO+VBO for a chunk mesh. Must run on the GL thread.
     */
    public static void uploadChunkMesh(WorldChunk chunk, FloatBuffer vertexData) {
        if (chunk.vaoHandle == 0) {
            chunk.vaoHandle = glGenVertexArrays();
        }
        if (chunk.vboVertexHandle == 0) {
            chunk.vboVertexHandle = glGenBuffers();
        }

        glBindVertexArray(chunk.vaoHandle);
        glBindBuffer(GL_ARRAY_BUFFER, chunk.vboVertexHandle);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 3L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 6L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 2, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 10L * Float.BYTES);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 12L * Float.BYTES);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Recomputes sky, sun and moon lighting for the given time of day.
     *
     * @param timeOfDay 0 and 1 are midnight, 0.25 sunrise, 0.5 noon, 0.75 sunset
     * @param dayCount  whole days elapsed, which drives the moon phase
     */
    public static void updateSky(float timeOfDay, int dayCount) {
        int preset = WorldPreset.clamp(World.WORLD_PRESET);
        cloudsEnabled = WorldPreset.hasClouds(preset);
        cloudCoverage = WorldPreset.cloudCoverage(preset);
        cloudSharpness = WorldPreset.cloudSharpness(preset);
        cloudOpacity = WorldPreset.cloudOpacity(preset);
        cloudShadowStrength = WorldPreset.cloudShadowStrength(preset);
        cloudBaseHeight = WorldPreset.cloudBaseHeight(preset);
        cloudLayerDepth = WorldPreset.cloudLayerDepth(preset);
        cloudSpeed = WorldPreset.cloudSpeed(preset);
        double angle = (timeOfDay - 0.25) * 2.0 * Math.PI;
        float elevation = (float) Math.sin(angle);
        float azimuth = (float) Math.cos(angle);
        sunElevation = elevation;

        // Direction the light travels, i.e. from the sun toward the ground.
        float len = (float) Math.sqrt(azimuth * azimuth + elevation * elevation + 0.35f * 0.35f);
        sunDirX = -azimuth / len;
        sunDirY = -elevation / len;
        sunDirZ = -0.35f / len;

        // Orbital position of each body as a fraction of a turn. The moon runs a
        // longer day, so it rises later each night and its phase advances.
        double sunOrbit = timeOfDay;
        // The half turn offset starts a new world on a full moon at midnight,
        // instead of a new moon sitting invisibly on top of the sun.
        double moonOrbit = (dayCount + timeOfDay) / LUNAR_PERIOD_DAYS + 0.5;

        double moonAngle = (moonOrbit - 0.25) * 2.0 * Math.PI;
        float moonElev = (float) Math.sin(moonAngle);
        float moonAzim = (float) Math.cos(moonAngle);
        float moonTilt = 0.55f;   // offset track, not the sun's mirror
        float moonLen = (float) Math.sqrt(moonAzim * moonAzim + moonElev * moonElev + moonTilt * moonTilt);
        moonDirX = -moonAzim / moonLen;
        moonDirY = -moonElev / moonLen;
        moonDirZ = -moonTilt / moonLen;
        moonElevation = moonElev;

        // Mars moon orbits (Phobos-like and Deimos-like): separate arcs and rates.
        double marsMoonAOrbit = (dayCount + timeOfDay) / 0.36;
        double marsMoonBOrbit = (dayCount + timeOfDay) / 1.25 + 0.22;
        double marsMoonAAngle = (marsMoonAOrbit - 0.25) * 2.0 * Math.PI;
        double marsMoonBAngle = (marsMoonBOrbit - 0.25) * 2.0 * Math.PI;
        float marsMoonAElev = 0.08f + 0.46f * (float) Math.sin(marsMoonAAngle);
        float marsMoonAAzim = (float) Math.cos(marsMoonAAngle);
        float marsMoonATilt = 0.22f;
        float marsMoonALen = (float) Math.sqrt(marsMoonAAzim * marsMoonAAzim
                + marsMoonAElev * marsMoonAElev + marsMoonATilt * marsMoonATilt);
        marsMoonADirX = -marsMoonAAzim / marsMoonALen;
        marsMoonADirY = -marsMoonAElev / marsMoonALen;
        marsMoonADirZ = -marsMoonATilt / marsMoonALen;

        float marsMoonBElev = -0.12f + 0.36f * (float) Math.sin(marsMoonBAngle);
        float marsMoonBAzim = (float) Math.cos(marsMoonBAngle);
        float marsMoonBTilt = -0.54f;
        float marsMoonBLen = (float) Math.sqrt(marsMoonBAzim * marsMoonBAzim
                + marsMoonBElev * marsMoonBElev + marsMoonBTilt * marsMoonBTilt);
        marsMoonBDirX = -marsMoonBAzim / marsMoonBLen;
        marsMoonBDirY = -marsMoonBElev / marsMoonBLen;
        marsMoonBDirZ = -marsMoonBTilt / marsMoonBLen;

        // Neptune as seen from Triton: slow drift, always high in the sky.
        double neptuneOrbit = (dayCount + timeOfDay) / 22.0 + 0.38;
        double neptuneAngle = (neptuneOrbit - 0.25) * 2.0 * Math.PI;
        float neptuneElev = 0.34f + 0.10f * (float) Math.sin(neptuneAngle * 0.7);
        float neptuneAzim = (float) Math.cos(neptuneAngle);
        float neptuneTilt = -0.62f;
        float neptuneLen = (float) Math.sqrt(neptuneAzim * neptuneAzim
                + neptuneElev * neptuneElev + neptuneTilt * neptuneTilt);
        neptuneDirX = -neptuneAzim / neptuneLen;
        neptuneDirY = -neptuneElev / neptuneLen;
        neptuneDirZ = -neptuneTilt / neptuneLen;

        // Phase comes from the signed orbital separation. Taking acos of the two
        // direction vectors instead discards the sign, which capped the cycle at
        // half its phases and never produced a waning moon.
        // Elongation increases over time: the moon lags the sun's daily motion,
        // so it drifts eastward and the phase advances new to full to new.
        // Using (moon - sun) instead runs the named phases backwards.
        double phaseFraction = (sunOrbit - moonOrbit) % 1.0;
        if (phaseFraction < 0) {
            phaseFraction += 1.0;
        }
        moonPhaseAngle = (float) (phaseFraction * 2.0 * Math.PI);
        moonIllumination = (1.0f - (float) Math.cos(moonPhaseAngle)) * 0.5f;
        moonPhase = (int) Math.round(phaseFraction * 8.0) % 8;

        float day = smoothstep(-0.10f, 0.22f, elevation);
        float night = 1.0f - day;

        // Sunlight reddens near the horizon because it travels a longer path
        // through the atmosphere, scattering blue away first.
        float horizon = (float) Math.exp(-(elevation * 3.0) * (elevation * 3.0));
        float dusk = horizon * smoothstep(-0.30f, 0.05f, elevation);

        float moonUp = Math.max(-moonDirY, 0.0f);
        // Moonlight depends on the moon's own elevation and phase, not on
        // whether the sun happens to be up, since both can share the sky.
        float moonStrength = moonIllumination * moonUp;
        // It is only perceptible once the sun stops washing it out.
        float moonVisibleStrength = moonStrength * night;
        dayFactor = day;
        duskFactor = dusk;
        moonGlowFactor = moonVisibleStrength;

        // Sky: blue by day, warm at the horizon, dark blue at night, lifted
        // slightly by a bright moon.
        skyR = lerp(0.04f, 0.52f, day) + dusk * 0.42f + moonVisibleStrength * 0.05f;
        skyG = lerp(0.06f, 0.80f, day) - dusk * 0.12f + moonVisibleStrength * 0.07f;
        skyB = lerp(0.14f, 0.92f, day) - dusk * 0.30f + moonVisibleStrength * 0.12f;

        // Direct sunlight warms toward amber near the horizon. Mixing between two
        // positive colours keeps every channel valid; adding red while
        // subtracting green and blue drove them negative, which lit terrain with
        // pure red at low sun angles.
        float warmR = lerp(0.72f, 0.70f, dusk);
        float warmG = lerp(0.66f, 0.42f, dusk);
        float warmB = lerp(0.56f, 0.28f, dusk);
        sunR = warmR * day;
        sunG = warmG * day;
        sunB = warmB * day;

        // The visible disc reddens harder than the light it casts.
        sunDiscR = 1.0f;
        sunDiscG = lerp(0.95f, 0.52f, dusk);
        sunDiscB = lerp(0.82f, 0.24f, dusk);

        // Fog takes a desaturated horizon tint so distant terrain melts into the
        // sky. A saturated sky colour here turns distant hillsides cyan, and a
        // saturated sunset colour turns them red, so both ends stay pale.
        fogR = lerp(0.10f, 0.75f, day);
        fogG = lerp(0.13f, 0.80f, day);
        fogB = lerp(0.22f, 0.86f, day);
        float warmth = dusk * 0.55f;
        fogR = lerp(fogR, 0.80f, warmth);
        fogG = lerp(fogG, 0.60f, warmth);
        fogB = lerp(fogB, 0.55f, warmth);
        // Dynamic fog parameters: clearer at midday, thicker around dusk and
        // night, with height falloff so valleys hold mist and high ground stays
        // cleaner.
        fogDensity = lerp(0.014f, 0.010f, day);
        fogDensity = lerp(fogDensity, fogDensity * 1.22f, dusk);
        fogHeightFalloff = lerp(0.050f, 0.034f, day);
        fogBaseHeight = lerp(28.0f, 34.0f, day);
        fogValleyTop = fogBaseHeight + lerp(1.0f, 3.0f, dusk);
        fogValleyStrength = lerp(0.30f, 0.18f, day);
        fogValleyStrength = lerp(fogValleyStrength, fogValleyStrength * 1.15f, dusk);

        // Cool, dim moonlight so nights read as lit rather than merely dark.
        moonR = 0.30f * moonVisibleStrength;
        moonG = 0.34f * moonVisibleStrength;
        moonB = 0.46f * moonVisibleStrength;

        // Night keeps a raised blue skylight so the world stays navigable, and a
        // bright moon lifts it further.
        float nightAmbient = 0.15f + 0.13f * moonStrength;
        ambR = lerp(nightAmbient * 0.85f, 0.40f, day);
        ambG = lerp(nightAmbient * 0.95f, 0.45f, day);
        ambB = lerp(nightAmbient * 1.35f, 0.55f, day);
        // A touch of warmth in the ambient at dusk, kept gentle.
        ambR = lerp(ambR, ambR * 1.18f, dusk);
        ambB = lerp(ambB, ambB * 0.90f, dusk);

        groundR = lerp(0.07f, 0.22f, day);
        groundG = lerp(0.08f, 0.20f, day);
        groundB = lerp(0.13f, 0.17f, day);

        if (preset == WorldPreset.MARS) {
            moonDirX = marsMoonADirX;
            moonDirY = marsMoonADirY;
            moonDirZ = marsMoonADirZ;
            // Thin CO2 atmosphere: rusty daytime with blue twilight.
            skyR = lerp(0.035f, 0.66f, day) - dusk * 0.08f;
            skyG = lerp(0.030f, 0.44f, day) - dusk * 0.02f;
            skyB = lerp(0.080f, 0.30f, day) + dusk * 0.28f;

            fogR = lerp(0.07f, 0.64f, day);
            fogG = lerp(0.06f, 0.46f, day);
            fogB = lerp(0.10f, 0.32f, day);
            fogR = lerp(fogR, 0.42f, dusk * 0.55f);
            fogG = lerp(fogG, 0.53f, dusk * 0.55f);
            fogB = lerp(fogB, 0.74f, dusk * 0.55f);
            fogDensity = lerp(0.010f, 0.0075f, day);
            fogHeightFalloff = lerp(0.040f, 0.028f, day);
            fogBaseHeight = lerp(24.0f, 30.0f, day);
            fogValleyTop = fogBaseHeight + 2.0f;
            fogValleyStrength = lerp(0.18f, 0.10f, day);

            float marsNightAmbient = 0.12f + 0.09f * moonStrength;
            ambR = lerp(marsNightAmbient * 0.90f, 0.34f, day);
            ambG = lerp(marsNightAmbient * 0.85f, 0.31f, day);
            ambB = lerp(marsNightAmbient * 0.90f, 0.33f, day);
            groundR = lerp(0.08f, 0.24f, day);
            groundG = lerp(0.07f, 0.17f, day);
            groundB = lerp(0.09f, 0.13f, day);
        } else if (preset == WorldPreset.VENUS) {
            // Thick sulfur haze: yellow atmosphere and strong volumetric fog.
            duskFactor *= 0.55f;
            skyR = lerp(0.14f, 0.82f, day) + duskFactor * 0.10f;
            skyG = lerp(0.12f, 0.72f, day) + duskFactor * 0.05f;
            skyB = lerp(0.08f, 0.30f, day) - duskFactor * 0.08f;

            fogR = lerp(0.26f, 0.93f, day);
            fogG = lerp(0.20f, 0.80f, day);
            fogB = lerp(0.12f, 0.40f, day);
            fogDensity = lerp(0.050f, 0.032f, day);
            fogHeightFalloff = lerp(0.020f, 0.014f, day);
            fogBaseHeight = lerp(34.0f, 42.0f, day);
            fogValleyTop = fogBaseHeight + 5.0f;
            fogValleyStrength = lerp(0.22f, 0.16f, day);

            sunR = 0.98f * day;
            sunG = 0.88f * day;
            sunB = 0.60f * day;
            sunDiscR = 1.00f;
            sunDiscG = lerp(0.92f, 0.70f, duskFactor);
            sunDiscB = lerp(0.62f, 0.36f, duskFactor);

            moonR = 0.0f;
            moonG = 0.0f;
            moonB = 0.0f;
            moonGlowFactor = 0.0f;

            ambR = lerp(0.17f, 0.52f, day);
            ambG = lerp(0.14f, 0.44f, day);
            ambB = lerp(0.10f, 0.26f, day);
            groundR = lerp(0.10f, 0.26f, day);
            groundG = lerp(0.08f, 0.18f, day);
            groundB = lerp(0.06f, 0.11f, day);
        } else if (preset == WorldPreset.TRITON) {
            // Far from the sun: almost airless, very dark sky and weak sunlight.
            duskFactor *= 0.15f;
            skyR = lerp(0.008f, 0.060f, day);
            skyG = lerp(0.010f, 0.080f, day);
            skyB = lerp(0.020f, 0.140f, day);

            fogR = lerp(0.010f, 0.070f, day);
            fogG = lerp(0.012f, 0.080f, day);
            fogB = lerp(0.018f, 0.120f, day);
            fogDensity = lerp(0.0018f, 0.0010f, day);
            fogHeightFalloff = lerp(0.070f, 0.050f, day);
            fogBaseHeight = lerp(22.0f, 28.0f, day);
            fogValleyTop = fogBaseHeight + 1.0f;
            fogValleyStrength = 0.03f;

            float solarScale = WorldPreset.solarIntensity(preset);
            sunR *= solarScale;
            sunG *= solarScale;
            sunB *= solarScale;
            sunDiscR *= solarScale;
            sunDiscG *= solarScale;
            sunDiscB *= solarScale;

            moonR = 0.0f;
            moonG = 0.0f;
            moonB = 0.0f;
            moonGlowFactor = 0.0f;

            ambR = lerp(0.05f, 0.20f, day);
            ambG = lerp(0.06f, 0.22f, day);
            ambB = lerp(0.09f, 0.28f, day);
            groundR = lerp(0.03f, 0.12f, day);
            groundG = lerp(0.04f, 0.13f, day);
            groundB = lerp(0.06f, 0.18f, day);
        } else {
            // Earth baseline intensity.
            sunR *= 1.00f;
            sunG *= 1.00f;
            sunB *= 1.00f;
        }
        sunCloudOcclusion = computeSunCloudOcclusion(preset);

        glClearColor(skyR, skyG, skyB, 1.0f);
    }

    /**
     * Lunar day length relative to the solar day. 8/7 makes the phase cycle
     * take exactly eight days, one per named phase.
     */
    private static final double LUNAR_PERIOD_DAYS = 8.0 / 7.0;

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    private static float computeSunCloudOcclusion(int preset) {
        if (!cloudsEnabled || Game.GAME_CAMERA == null || Game.GAME_CAMERA.position == null) {
            return 1.0f;
        }
        float toSunX = -sunDirX;
        float toSunY = -sunDirY;
        float toSunZ = -sunDirZ;
        if (toSunY <= 0.02f) {
            return 1.0f;
        }
        float camX = (float) Game.GAME_CAMERA.position.x;
        float camY = (float) Game.GAME_CAMERA.position.y;
        float camZ = (float) Game.GAME_CAMERA.position.z;

        float slabTop = cloudBaseHeight + cloudLayerDepth;
        float tEnter = (cloudBaseHeight - camY) / toSunY;
        float tExit = (slabTop - camY) / toSunY;
        float t0 = Math.max(Math.min(tEnter, tExit), 0.0f);
        float t1 = Math.min(Math.max(tEnter, tExit), t0 + cloudLayerDepth * 10.0f);
        if (t1 <= t0) {
            return 1.0f;
        }

        float time = (float) org.lwjgl.glfw.GLFW.glfwGetTime();
        float dayClock = Game.DAY_COUNT + Game.TIME_OF_DAY;
        float wx = time * cloudSpeed * 6.0f;
        float wz = -time * cloudSpeed * 2.5f;
        float ex = (float) Math.sin(dayClock * 2.324 + time * 0.017) * 96.0f;
        float ez = (float) Math.cos(dayClock * 1.447 - time * 0.013) * 96.0f;

        float midT = (t0 + t1) * 0.5f;
        float regime = fbm((camX + toSunX * midT + ex * 0.35f + wx * 0.08f) * 0.00075f,
                (camZ + toSunZ * midT + ez * 0.35f + wz * 0.08f) * 0.00075f);

        final int steps = 5;
        float dt = (t1 - t0) / steps;
        float depth = 0.0f;
        for (int i = 0; i < steps; i++) {
            float t = t0 + dt * (i + 0.5f);
            depth += cloudDensityCoarseAt(camX + toSunX * t, camY + toSunY * t, camZ + toSunZ * t,
                    wx, wz, ex, ez, regime, preset) * dt;
        }

        float sigma = lerp(0.055f, 0.140f, cloudOpacity);
        if (preset == WorldPreset.VENUS) {
            sigma *= 1.8f;
        }
        return clamp01((float) Math.exp(-depth * sigma));
    }

    /** CPU mirror of cloudDensityCoarse in skygradient.frag. */
    private static float cloudDensityCoarseAt(float x, float y, float z,
            float wx, float wz, float ex, float ez, float regime, int preset) {
        float hN = (y - cloudBaseHeight) / Math.max(cloudLayerDepth, 1.0f);
        if (hN < 0.0f || hN > 1.0f) {
            return 0.0f;
        }
        float profile = preset == WorldPreset.VENUS
                ? smoothstep(0.0f, 0.10f, hN) * (1.0f - smoothstep(0.72f, 1.0f, hN))
                : smoothstep(0.0f, 0.13f, hN) * (1.0f - smoothstep(0.42f, 1.0f, hN));
        if (profile <= 0.002f) {
            return 0.0f;
        }
        float sx = x + hN * 34.0f;
        float sz = z - hN * 22.0f;
        return cloudMaskAt(sx, sz, wx, wz, ex, ez, regime, preset) * profile;
    }

    /** CPU mirror of cloudMaskAt in skygradient.frag. */
    private static float cloudMaskAt(float sx, float sz,
            float wx, float wz, float ex, float ez, float regime, int preset) {
        float n0 = fbm3((sx + wx + ex) * 0.0022f, (sz + wz + ez) * 0.0022f);
        float n1 = fbm3((sx - wx * 0.65f + ez * 0.7f) * 0.0041f,
                (sz - wz * 0.65f + ex * 0.7f) * 0.0041f);
        float n = lerp(n0, n1, 0.40f);

        if (preset == WorldPreset.VENUS) {
            float deck = smoothstep(cloudCoverage - 0.18f, cloudCoverage + cloudSharpness * 2.8f, lerp(n0, n1, 0.25f));
            float billow = smoothstep(cloudCoverage - 0.06f, cloudCoverage + cloudSharpness * 1.6f, n0);
            return clamp01(lerp(deck, billow, 0.35f));
        }

        float cumulusBase = smoothstep(cloudCoverage - cloudSharpness, cloudCoverage + cloudSharpness, n);
        float cumulusCrisp = smoothstep(cloudCoverage - cloudSharpness * 0.30f,
                cloudCoverage + cloudSharpness * 0.20f,
                n + (n1 - 0.5f) * 0.14f);
        float cumulus = lerp(cumulusBase, cumulusCrisp, 0.55f) * (1.0f - smoothstep(0.55f, 0.88f, regime));
        float stratus = smoothstep(cloudCoverage - 0.12f, cloudCoverage + 0.18f, n0)
                * smoothstep(0.42f, 0.95f, regime);
        float cirrus = smoothstep(cloudCoverage - 0.30f, cloudCoverage - 0.08f, n1)
                * (1.0f - smoothstep(0.28f, 0.70f, regime)) * 0.35f;
        return clamp01(Math.max(cumulus, stratus * 0.92f) + cirrus);
    }

    private static float hash12(float x, float z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        long n = (long) xi * 374761393L + (long) zi * 668265263L;
        n = (n ^ (n >> 13)) * 1274126177L;
        n = n ^ (n >> 16);
        return (n & 0x7FFFFFFFL) / (float) 0x7FFFFFFF;
    }

    private static float valueNoise(float x, float z) {
        float ix = (float) Math.floor(x);
        float iz = (float) Math.floor(z);
        float fx = x - ix;
        float fz = z - iz;
        float ux = fx * fx * (3.0f - 2.0f * fx);
        float uz = fz * fz * (3.0f - 2.0f * fz);
        float a = hash12(ix, iz);
        float b = hash12(ix + 1.0f, iz);
        float c = hash12(ix, iz + 1.0f);
        float d = hash12(ix + 1.0f, iz + 1.0f);
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uz);
    }

    private static float fbm(float x, float z) {
        float f = 0.0f;
        float amp = 0.55f;
        float freq = 1.0f;
        for (int i = 0; i < 4; i++) {
            f += amp * valueNoise(x * freq, z * freq);
            freq *= 2.0f;
            amp *= 0.55f;
        }
        return f;
    }

    /** CPU mirror of fbm3 in the cloud shaders. */
    private static float fbm3(float x, float z) {
        float f = 0.0f;
        float amp = 0.58f;
        float freq = 1.0f;
        for (int i = 0; i < 3; i++) {
            f += amp * valueNoise(x * freq, z * freq);
            freq *= 2.0f;
            amp *= 0.55f;
        }
        return f;
    }

    /**
     * Draws the sun and moon on the far plane, before any terrain, so the world
     * occludes them naturally.
     */
    public static void drawCelestialBodies() {
        int preset = WorldPreset.clamp(World.WORLD_PRESET);
        // Strip the translation so the bodies stay fixed on the sky.
        viewRotationScratch.set(view);
        viewRotationScratch.m30(0f);
        viewRotationScratch.m31(0f);
        viewRotationScratch.m32(0f);

        skyShader.bind();
        skyShader.setMatrix4f("projection", projection);
        skyShader.setMatrix4f("viewRotation", viewRotationScratch);

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);   // additive, so bodies glow
        glDisable(GL_CULL_FACE);
        glBindVertexArray(skyVao);

        // Sun: cut off at the horizon so it does not hang below it. The fade is
        // narrow, since a body visibly below the horizon should be occluded by
        // the earth rather than drawn against the ground.
        float sunVisible = clamp01((-sunDirY + 0.01f) * 22.0f);
        if (sunVisible > 0.001f) {
            skyShader.setVector3f("bodyDirection", -sunDirX, -sunDirY, -sunDirZ);
            // The quad is much larger than the disc so the glow and rays have
            // room to fade out instead of ending at a hard edge.
            skyShader.setFloat("quadSize", 0.30f);
            skyShader.setFloat("discHalf", 0.30f);
            skyShader.setVector3f("bodyColor", sunDiscR, sunDiscG, sunDiscB);
            skyShader.setFloat("bodyAlpha", sunVisible * sunCloudOcclusion);
            skyShader.setBoolean("showRays", true);
            skyShader.setBoolean("roundBody", false);
            skyShader.setFloat("rayTime", (float) org.lwjgl.glfw.GLFW.glfwGetTime());
            skyShader.setFloat("glowStrength", 0.50f);
            bindBodyTexture(sunTexture, 0f, 0f, 1f, 1f);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        if (WorldPreset.hasEarthMoon(preset)) {
            // Moon: shown whenever it is above the horizon.
            float moonVisible = clamp01((-moonDirY + 0.01f) * 22.0f);
            if (moonVisible > 0.001f) {
                float daylight = smoothstep(-0.05f, 0.30f, sunElevation);
                float alpha = moonVisible * (1.0f - daylight * 0.62f);
                float u = (moonPhase % 4) * 0.25f;
                float v = (moonPhase / 4) * 0.5f;
                drawRoundBody(-moonDirX, -moonDirY, -moonDirZ,
                        0.150f, 0.52f, 0.94f, 0.95f, 1.0f,
                        alpha, 0.30f, moonTexture, u, v, 0.25f, 0.5f);
            }
        } else if (WorldPreset.hasDualMarsMoons(preset)) {
            // Two small moons on different arcs and schedules.
            float daylight = smoothstep(-0.05f, 0.30f, sunElevation);

            float moonAVisible = clamp01((-marsMoonADirY + 0.01f) * 20.0f);
            if (moonAVisible > 0.001f) {
                float alphaA = moonAVisible * (1.0f - daylight * 0.70f);
                drawRoundBody(-marsMoonADirX, -marsMoonADirY, -marsMoonADirZ,
                        0.090f, 0.52f, 0.92f, 0.90f, 0.86f,
                        alphaA, 0.20f, null, 0f, 0f, 1f, 1f);
            }

            float moonBVisible = clamp01((-marsMoonBDirY + 0.01f) * 20.0f);
            if (moonBVisible > 0.001f) {
                float alphaB = moonBVisible * (1.0f - daylight * 0.76f);
                drawRoundBody(-marsMoonBDirX, -marsMoonBDirY, -marsMoonBDirZ,
                        0.062f, 0.52f, 0.84f, 0.80f, 0.74f,
                        alphaB, 0.16f, null, 0f, 0f, 1f, 1f);
            }
        } else if (WorldPreset.hasNeptuneSkyBody(preset)) {
            // Triton has no moon in this preset; Neptune dominates the sky.
            float neptuneVisible = clamp01((-neptuneDirY + 0.01f) * 16.0f);
            if (neptuneVisible > 0.001f) {
                float alpha = neptuneVisible * 0.95f;
                drawRoundBody(-neptuneDirX, -neptuneDirY, -neptuneDirZ,
                        0.320f, 0.54f, 0.45f, 0.60f, 0.95f,
                        alpha, 0.34f, null, 0f, 0f, 1f, 1f);
            }
        }

        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        ShaderProgram.unbind();
    }

    private static void drawRoundBody(float dirX, float dirY, float dirZ,
                                      float quadSize, float discHalf,
                                      float r, float g, float b,
                                      float alpha, float glow,
                                      Texture texture,
                                      float u, float v, float du, float dv) {
        skyShader.setVector3f("bodyDirection", dirX, dirY, dirZ);
        skyShader.setFloat("quadSize", quadSize);
        skyShader.setFloat("discHalf", discHalf);
        skyShader.setVector3f("bodyColor", r, g, b);
        skyShader.setFloat("bodyAlpha", alpha);
        skyShader.setBoolean("showRays", false);
        skyShader.setBoolean("roundBody", true);
        skyShader.setFloat("glowStrength", glow);
        bindBodyTexture(texture, u, v, du, dv);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private static void bindBodyTexture(Texture texture, float u, float v, float du, float dv) {
        boolean has = texture != null;
        skyShader.setBoolean("useTexture", has);
        skyShader.setVector4f("uvRect", u, v, du, dv);
        if (has) {
            glActiveTexture(GL_TEXTURE0);
            texture.bind();
            skyShader.setInt("bodyTexture", 0);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Binds the chunk shader and uploads all per-frame uniforms once. */
    public static void beginChunkPass() {
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);

        chunkShader.bind();
        activePass = chunkShader;
        chunkShader.setMatrix4f("projection", projection);
        chunkShader.setMatrix4f("view", view);

        chunkShader.setVector3f("sunDirection", sunDirX, sunDirY, sunDirZ);
        chunkShader.setVector3f("sunColor", sunR, sunG, sunB);
        chunkShader.setVector3f("moonDirection", moonDirX, moonDirY, moonDirZ);
        chunkShader.setVector3f("moonColor", moonR, moonG, moonB);
        // Cool skylight from above, warm bounce from the ground below.
        chunkShader.setVector3f("skyAmbient", ambR, ambG, ambB);
        chunkShader.setVector3f("groundAmbient", groundR, groundG, groundB);
        chunkShader.setFloat("caveMinimum", Game.OPT_CAVE_MINIMUM_LIGHT);
        chunkShader.setFloat("aoStrength", Game.OPT_AMBIENT_OCCLUSION ? 1.0f : 0.0f);
        chunkShader.setFloat("alphaOverride", 1.0f);

        chunkShader.setBoolean("flashlightOn", Game.OPT_FLASHLIGHT);
        chunkShader.setVector3f("flashlightColor", 1.35f, 1.28f, 1.10f);
        chunkShader.setFloat("flashlightRange", 40.0f);
        chunkShader.setFloat("flashlightInner", 0.94f);
        chunkShader.setFloat("flashlightOuter", 0.82f);

        boolean textured = Game.OPT_USE_TEXTURES && Game.OPT_DRAW_TEXTURES && terrainTexture != null;
        chunkShader.setBoolean("useTexture", textured);
        if (textured) {
            glActiveTexture(GL_TEXTURE0);
            terrainTexture.bind();
            chunkShader.setInt("textureSampler", 0);
        }

        // Tinting an atlas tile by the block's palette color would double up the
        // hue, so vertex color only drives shading when textures are off.
        chunkShader.setBoolean("useVertexColor", Game.OPT_DRAW_COLORED_BLOCKS && !textured);

        chunkShader.setBoolean("fogEnabled", Game.OPT_FOG);
        chunkShader.setVector3f("fogColor", fogR, fogG, fogB);
        // Keep most fog behavior in world-space units so changing draw
        // distance does not erase the atmosphere. Draw distance only caps the
        // far blend used to hide horizon pop.
        float fogRange = Game.OPT_DRAW_DISTANCE * (float) WorldChunk.sizeX;
        float baseStart = lerp(150.0f, 125.0f, duskFactor);
        float baseEnd = lerp(380.0f, 330.0f, duskFactor);
        float fogEnd = Math.min(baseEnd, fogRange * 0.985f);
        float fogStart = Math.min(baseStart, fogEnd - 40.0f);
        chunkShader.setFloat("fogStart", fogStart);
        chunkShader.setFloat("fogEnd", fogEnd);
        chunkShader.setFloat("fogDensity", fogDensity * Game.OPT_FOG_DENSITY);
        chunkShader.setFloat("fogHeightFalloff", fogHeightFalloff);
        chunkShader.setFloat("fogBaseHeight", fogBaseHeight);
        chunkShader.setFloat("fogNoiseScale", fogNoiseScale);
        chunkShader.setFloat("fogTime", Game.DAY_COUNT + Game.TIME_OF_DAY);
        chunkShader.setFloat("fogTimeScale", fogTimeScale);
        chunkShader.setFloat("fogDayFactor", dayFactor);
        chunkShader.setFloat("fogDuskFactor", duskFactor);
        chunkShader.setFloat("fogValleyStrength", fogValleyStrength * Game.OPT_FOG_PERSISTENCE);
        chunkShader.setFloat("fogValleyTop", fogValleyTop);

        chunkShader.setBoolean("cloudsEnabled", cloudsEnabled);
        chunkShader.setFloat("cloudCoverage", cloudCoverage);
        chunkShader.setFloat("cloudSharpness", cloudSharpness);
        chunkShader.setFloat("cloudShadowStrength", cloudShadowStrength);
        chunkShader.setFloat("cloudBaseHeight", cloudBaseHeight);
        chunkShader.setFloat("cloudLayerDepth", cloudLayerDepth);
        chunkShader.setFloat("cloudTime", (float) org.lwjgl.glfw.GLFW.glfwGetTime());
        chunkShader.setFloat("cloudSpeed", cloudSpeed);
        chunkShader.setFloat("cloudDayTime", Game.DAY_COUNT + Game.TIME_OF_DAY);
        chunkShader.setInt("atmospherePreset", WorldPreset.clamp(World.WORLD_PRESET));
    }

    public static void renderChunkMesh(WorldChunk chunk) {
        if (chunk == null || chunk.numVerts <= 0 || chunk.vaoHandle == 0) {
            return;
        }

        int opaque = Math.min(chunk.opaqueVerts, chunk.numVerts);
        if (opaque > 0) {
            setChunkModel(chunk);
            glBindVertexArray(chunk.vaoHandle);
            glDrawArrays(GL_TRIANGLES, 0, opaque);
            glBindVertexArray(0);
        }

        if (chunk.numVerts > opaque) {
            translucentQueue.add(chunk);
        }
    }

    private static void setChunkModel(WorldChunk chunk) {
        modelScratch.identity().translate(
                (float) (chunk.worldPosX - Game.GAME_CAMERA.position.x),
                0.0f,
                (float) (chunk.worldPosY - Game.GAME_CAMERA.position.z));
        chunkShader.setMatrix4f("model", modelScratch);
    }

    public static void endChunkPass() {
        drawTranslucentQueue();
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        activePass = null;
        ShaderProgram.unbind();
    }

    /**
     * Draws water after all opaque geometry with depth writes disabled, so the
     * terrain behind it shows through.
     *
     * Chunks are submitted in the world's near-to-far spiral order rather than
     * sorted back-to-front, which is adequate for flat lake surfaces but would
     * need real sorting for stacked translucent volumes.
     */
    private static void drawTranslucentQueue() {
        if (translucentQueue.isEmpty()) {
            return;
        }
        glEnable(GL_BLEND);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        chunkShader.setFloat("alphaOverride", 0.62f);

        for (int i = translucentQueue.size() - 1; i >= 0; i--) {
            WorldChunk chunk = translucentQueue.get(i);
            if (chunk.vaoHandle == 0) {
                continue;
            }
            int opaque = Math.min(chunk.opaqueVerts, chunk.numVerts);
            setChunkModel(chunk);
            glBindVertexArray(chunk.vaoHandle);
            glDrawArrays(GL_TRIANGLES, opaque, chunk.numVerts - opaque);
            glBindVertexArray(0);
        }

        chunkShader.setFloat("alphaOverride", 1.0f);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        translucentQueue.clear();
    }

    /** Rebinds the pass shader, or unbinds when no pass is active. */
    private static void restoreActivePass() {
        if (activePass != null) {
            activePass.bind();
        } else {
            ShaderProgram.unbind();
        }
    }

    private static void ensureLineCapacity(int floats) {
        if (lineStaging != null && floats <= lineCapacityFloats) {
            return;
        }
        int newCapacity = Math.max(floats, lineCapacityFloats * 2);
        if (lineStaging != null) {
            MemoryUtil.memFree(lineStaging);
        }
        lineStaging = MemoryUtil.memAllocFloat(newCapacity);
        lineCapacityFloats = newCapacity;

        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) newCapacity * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Draws debug/UI geometry through a shader and a dynamic VBO instead of
     * glBegin/glEnd.
     *
     * @param positions tightly packed xyz triples
     * @param count     number of vertices to draw
     */
    public static void drawDebugGeometry(int mode, float[] positions, int count,
                                         Matrix4f model, float r, float g, float b, float a) {
        if (count <= 0) {
            return;
        }
        ensureLineCapacity(count * 3);

        lineStaging.clear();
        lineStaging.put(positions, 0, count * 3);
        lineStaging.flip();

        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, lineStaging);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        lineShader.bind();
        lineShader.setMatrix4f("projection", projection);
        lineShader.setMatrix4f("view", view);
        lineShader.setMatrix4f("model", model);
        lineShader.setVector4f("color", r, g, b, a);

        glBindVertexArray(lineVao);
        glDrawArrays(mode, 0, count);
        glBindVertexArray(0);
        restoreActivePass();
    }

    /**
     * Draws screen-space overlay geometry with an identity view and an
     * orthographic projection spanning [-1,1].
     */
    public static void drawOverlayGeometry(int mode, float[] positions, int count,
                                           float r, float g, float b, float a) {
        if (count <= 0) {
            return;
        }
        ensureLineCapacity(count * 3);

        lineStaging.clear();
        lineStaging.put(positions, 0, count * 3);
        lineStaging.flip();

        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, lineStaging);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        Matrix4f identity = new Matrix4f();
        lineShader.bind();
        lineShader.setMatrix4f("projection", identity);
        lineShader.setMatrix4f("view", identity);
        lineShader.setMatrix4f("model", identity);
        lineShader.setVector4f("color", r, g, b, a);

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(lineVao);
        glDrawArrays(mode, 0, count);
        glBindVertexArray(0);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        restoreActivePass();
    }

    private static void ensureHudCapacity(int floats) {
        if (hudStaging != null && floats <= hudCapacityFloats) {
            return;
        }
        int newCapacity = Math.max(floats, hudCapacityFloats * 2);
        if (hudStaging != null) {
            MemoryUtil.memFree(hudStaging);
        }
        hudStaging = MemoryUtil.memAllocFloat(newCapacity);
        hudCapacityFloats = newCapacity;

        glBindBuffer(GL_ARRAY_BUFFER, hudVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) newCapacity * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public static void drawHudQuads(float[] data, int vertexCount, boolean textured,
                                    float r, float g, float b, float a) {
        drawHudQuads(data, vertexCount, textured ? terrainTextureId() : 0, r, g, b, a);
    }

    private static int terrainTextureId() {
        return terrainTexture == null ? 0 : terrainTexture.getId();
    }

    /**
     * Draws screen-space HUD triangles.
     *
     * @param data        {x, y, u, v} per vertex, position already in NDC
     * @param vertexCount number of vertices in {@code data}
     * @param textureId   GL texture to sample, or 0 for a flat tint
     */
    public static void drawHudQuads(float[] data, int vertexCount, int textureId,
                                    float r, float g, float b, float a) {
        if (vertexCount <= 0) {
            return;
        }
        ensureHudCapacity(vertexCount * 4);

        hudStaging.clear();
        hudStaging.put(data, 0, vertexCount * 4);
        hudStaging.flip();

        glBindBuffer(GL_ARRAY_BUFFER, hudVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, hudStaging);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        hudShader.bind();
        hudShader.setBoolean("useTexture", textureId != 0);
        hudShader.setVector4f("tint", r, g, b, a);
        if (textureId != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
            hudShader.setInt("textureSampler", 0);
        }

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(hudVao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        restoreActivePass();
    }

    public static void cleanup() {
        if (chunkShader != null) {
            chunkShader.cleanup();
        }
        if (lineShader != null) {
            lineShader.cleanup();
        }
        if (skyShader != null) {
            skyShader.cleanup();
        }
        if (skyGradientShader != null) {
            skyGradientShader.cleanup();
        }
        if (godRayShader != null) {
            godRayShader.cleanup();
        }
        if (compositeShader != null) {
            compositeShader.cleanup();
        }
        if (sceneBuffer != null) {
            sceneBuffer.cleanup();
        }
        if (raysBuffer != null) {
            raysBuffer.cleanup();
        }
        if (fullscreenVbo != 0) {
            glDeleteBuffers(fullscreenVbo);
        }
        if (fullscreenVao != 0) {
            glDeleteVertexArrays(fullscreenVao);
        }
        if (skyVbo != 0) {
            glDeleteBuffers(skyVbo);
        }
        if (skyVao != 0) {
            glDeleteVertexArrays(skyVao);
        }
        if (hudShader != null) {
            hudShader.cleanup();
        }
        if (hudStaging != null) {
            MemoryUtil.memFree(hudStaging);
            hudStaging = null;
        }
        if (hudVbo != 0) {
            glDeleteBuffers(hudVbo);
        }
        if (hudVao != 0) {
            glDeleteVertexArrays(hudVao);
        }
        if (terrainTexture != null) {
            terrainTexture.cleanup();
        }
        if (sunTexture != null) {
            sunTexture.cleanup();
        }
        if (moonTexture != null) {
            moonTexture.cleanup();
        }
        TextRenderer.cleanup();
        if (lineStaging != null) {
            MemoryUtil.memFree(lineStaging);
            lineStaging = null;
        }
        if (lineVbo != 0) {
            glDeleteBuffers(lineVbo);
        }
        if (lineVao != 0) {
            glDeleteVertexArrays(lineVao);
        }
    }
}
