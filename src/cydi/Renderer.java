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
    private static int skyVao;
    private static int skyVbo;

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
    private static boolean wireframe;

    /** Chunks holding translucent geometry, drawn after all opaque chunks. */
    private static final java.util.ArrayList<WorldChunk> translucentQueue = new java.util.ArrayList<>();

    /** Sky and sun state for the current time of day. */
    private static float skyR = 0.52f, skyG = 0.80f, skyB = 0.92f;
    private static float sunDirX = -0.35f, sunDirY = -1.0f, sunDirZ = -0.45f;
    private static float sunR = 0.68f, sunG = 0.64f, sunB = 0.55f;
    private static float moonDirX = 0.35f, moonDirY = 1.0f, moonDirZ = 0.45f;
    private static float moonR = 0f, moonG = 0f, moonB = 0f;
    private static float ambR = 0.40f, ambG = 0.45f, ambB = 0.55f;
    private static float groundR = 0.22f, groundG = 0.20f, groundB = 0.17f;
    private static float sunElevation = 1f;
    private static float moonElevation = -1f;
    private static float moonIllumination = 1f;
    private static float moonPhaseAngle = (float) Math.PI;
    private static int moonPhase = 0;

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

        terrainTexture = Texture.loadOrNull("media/art/terrain.png");
        if (terrainTexture == null) {
            Game.OPT_USE_TEXTURES = false;
            System.out.println("No terrain texture found; falling back to vertex colors.");
        }
    }

    public static void setAntialiasing(boolean enabled) {
        if (enabled) {
            glEnable(GL_MULTISAMPLE);
        } else {
            glDisable(GL_MULTISAMPLE);
        }
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

        // The moon keeps its own slightly longer day, so it drifts against the
        // sun instead of sitting exactly opposite it, and rises a little later
        // each night. Its orbit is also tilted onto a different track.
        double lunarDay = (dayCount + timeOfDay) / LUNAR_PERIOD_DAYS;
        double moonAngle = (lunarDay - 0.25) * 2.0 * Math.PI;
        float moonElev = (float) Math.sin(moonAngle);
        float moonAzim = (float) Math.cos(moonAngle);
        float moonTilt = 0.55f;   // offset track, not the sun's mirror
        float moonLen = (float) Math.sqrt(moonAzim * moonAzim + moonElev * moonElev + moonTilt * moonTilt);
        moonDirX = -moonAzim / moonLen;
        moonDirY = -moonElev / moonLen;
        moonDirZ = -moonTilt / moonLen;
        moonElevation = moonElev;

        float day = smoothstep(-0.10f, 0.22f, elevation);
        float night = 1.0f - day;
        // Warm band that peaks while the sun sits near the horizon.
        float dusk = (float) Math.exp(-(elevation * 5.0) * (elevation * 5.0)) * day;

        // Phase follows the angle between the sun and moon directions, which is
        // what actually drives it, rather than being keyed off the day counter.
        float cosSep = sunDirX * moonDirX + sunDirY * moonDirY + sunDirZ * moonDirZ;
        float separation = (float) Math.acos(Math.max(-1.0, Math.min(1.0, cosSep)));
        moonIllumination = (1.0f - (float) Math.cos(separation)) * 0.5f;
        moonPhaseAngle = separation;
        moonPhase = (int) Math.round(separation / Math.PI * 4.0) % 8;

        float moonUp = Math.max(-moonDirY, 0.0f);
        // Moonlight depends on the moon's own elevation and phase, not on
        // whether the sun happens to be up, since both can share the sky.
        float moonStrength = moonIllumination * moonUp;
        // It is only perceptible once the sun stops washing it out.
        float moonVisibleStrength = moonStrength * night;

        skyR = lerp(0.04f, 0.52f, day) + dusk * 0.42f + moonVisibleStrength * 0.05f;
        skyG = lerp(0.06f, 0.80f, day) + dusk * 0.10f + moonVisibleStrength * 0.07f;
        skyB = lerp(0.14f, 0.92f, day) - dusk * 0.10f + moonVisibleStrength * 0.12f;

        sunR = lerp(0.00f, 0.72f, day) + dusk * 0.25f;
        sunG = lerp(0.00f, 0.66f, day) - dusk * 0.05f;
        sunB = lerp(0.00f, 0.56f, day) - dusk * 0.12f;

        // Cool, dim moonlight so nights read as lit rather than merely dark.
        moonR = 0.22f * moonVisibleStrength;
        moonG = 0.26f * moonVisibleStrength;
        moonB = 0.38f * moonVisibleStrength;

        // Night keeps a raised blue skylight so the world stays navigable.
        float nightAmbient = 0.16f + 0.10f * moonIllumination;
        ambR = lerp(nightAmbient * 0.85f, 0.40f, day);
        ambG = lerp(nightAmbient * 0.95f, 0.45f, day);
        ambB = lerp(nightAmbient * 1.35f, 0.55f, day);

        groundR = lerp(0.07f, 0.22f, day);
        groundG = lerp(0.08f, 0.20f, day);
        groundB = lerp(0.13f, 0.17f, day);

        glClearColor(skyR, skyG, skyB, 1.0f);
    }

    /** Lunar day length relative to the solar day, so the moon drifts. */
    private static final double LUNAR_PERIOD_DAYS = 1.035;

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
            skyShader.setFloat("discRadius", 0.17f);
            skyShader.setVector3f("bodyColor", 1.0f, 0.95f, 0.82f);
            skyShader.setFloat("bodyAlpha", sunVisible);
            skyShader.setBoolean("showPhase", false);
            skyShader.setBoolean("showRays", true);
            skyShader.setFloat("rayTime", (float) org.lwjgl.glfw.GLFW.glfwGetTime());
            skyShader.setFloat("glowStrength", 0.55f);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        // Moon: shown whenever it is above the horizon. The two orbits differ,
        // so a daylit moon is expected rather than something to suppress.
        float moonVisible = clamp01((-moonDirY + 0.12f) * 4.0f);
        if (moonVisible > 0.001f && moonIllumination > 0.02f) {
            // Fade it back in daylight so it reads as a pale daytime moon.
            float daylight = smoothstep(-0.05f, 0.30f, sunElevation);
            float alpha = moonVisible * (1.0f - daylight * 0.62f);

            skyShader.setVector3f("bodyDirection", -moonDirX, -moonDirY, -moonDirZ);
            skyShader.setFloat("quadSize", 0.13f);
            skyShader.setFloat("discRadius", 0.62f);
            skyShader.setVector3f("bodyColor", 0.90f, 0.92f, 1.0f);
            skyShader.setFloat("bodyAlpha", alpha);
            skyShader.setBoolean("showPhase", true);
            skyShader.setBoolean("showRays", false);
            skyShader.setFloat("phaseAngle", moonPhaseAngle);
            skyShader.setFloat("glowStrength", 0.10f);
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
        chunkShader.setVector3f("fogColor", skyR, skyG, skyB);
        // Fog must finish inside the draw distance so chunk pop-in is hidden.
        float fogRange = Game.OPT_DRAW_DISTANCE * (float) WorldChunk.sizeX;
        chunkShader.setFloat("fogStart", fogRange * 0.60f);
        chunkShader.setFloat("fogEnd", fogRange * 0.95f);
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
