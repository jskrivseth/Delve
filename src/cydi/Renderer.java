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
    private static float sunDirX = -0.35f, sunDirY = -1.0f, sunDirZ = -0.45f;
    private static float sunR = 0.68f, sunG = 0.64f, sunB = 0.55f;
    private static float sunDiscR = 1.0f, sunDiscG = 0.95f, sunDiscB = 0.82f;
    private static float moonDirX = 0.35f, moonDirY = 1.0f, moonDirZ = 0.45f;
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

        if (dirY <= 0.02f) {
            return false;   // below the horizon, no shafts
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

    /**
     * Draws the sun and moon on the far plane, before any terrain, so the world
     * occludes them naturally.
     */
    public static void drawCelestialBodies() {
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

        // Sun: visible whenever it is above the horizon.
        float sunVisible = clamp01((-sunDirY + 0.12f) * 4.0f);
        if (sunVisible > 0.001f) {
            skyShader.setVector3f("bodyDirection", -sunDirX, -sunDirY, -sunDirZ);
            // The quad is much larger than the disc so the glow and rays have
            // room to fade out instead of ending at a hard edge.
            skyShader.setFloat("quadSize", 0.30f);
            skyShader.setFloat("discHalf", 0.30f);
            skyShader.setVector3f("bodyColor", sunDiscR, sunDiscG, sunDiscB);
            skyShader.setFloat("bodyAlpha", sunVisible);
            skyShader.setBoolean("showRays", true);
            skyShader.setBoolean("roundBody", false);
            skyShader.setFloat("rayTime", (float) org.lwjgl.glfw.GLFW.glfwGetTime());
            skyShader.setFloat("glowStrength", 0.50f);
            bindBodyTexture(sunTexture, 0f, 0f, 1f, 1f);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        // Moon: shown whenever it is above the horizon. The two orbits differ,
        // so a daylit moon is expected rather than something to suppress.
        float moonVisible = clamp01((-moonDirY + 0.12f) * 4.0f);
        if (moonVisible > 0.001f) {
            // Fade it back in daylight so it reads as a pale daytime moon.
            float daylight = smoothstep(-0.05f, 0.30f, sunElevation);
            float alpha = moonVisible * (1.0f - daylight * 0.62f);

            skyShader.setVector3f("bodyDirection", -moonDirX, -moonDirY, -moonDirZ);
            // A wider quad relative to the disc leaves room for the halo.
            skyShader.setFloat("quadSize", 0.150f);
            skyShader.setFloat("discHalf", 0.52f);
            skyShader.setVector3f("bodyColor", 0.94f, 0.95f, 1.0f);
            skyShader.setFloat("bodyAlpha", alpha);
            skyShader.setBoolean("showRays", false);
            skyShader.setBoolean("roundBody", true);
            skyShader.setFloat("glowStrength", 0.30f);
            // moon_phases.png is a 4x2 grid; select the current phase cell.
            float u = (moonPhase % 4) * 0.25f;
            float v = (moonPhase / 4) * 0.5f;
            bindBodyTexture(moonTexture, u, v, 0.25f, 0.5f);
            glDrawArrays(GL_TRIANGLES, 0, 6);
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
        // Fog must finish inside the draw distance so chunk pop-in is hidden,
        // but starting it too near makes mid-range terrain read as solid haze.
        float fogRange = Game.OPT_DRAW_DISTANCE * (float) WorldChunk.sizeX;
        chunkShader.setFloat("fogStart", fogRange * 0.72f);
        chunkShader.setFloat("fogEnd", fogRange * 0.98f);
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
