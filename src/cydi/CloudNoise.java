package cydi;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * Baked replacement for the value-noise lattice the cloud shaders sample.
 *
 * clouds.glsl's fbm/fbm3/billow3 each evaluate the same underlying hash-based
 * lattice several times per density sample -- coverage, mask, drape, lumps and
 * billow together were doing on the order of fifteen hash evaluations per
 * pixel per march step. Baking the lattice into a small tileable texture once
 * at startup turns every one of those evaluations into a single
 * hardware-filtered texture fetch: the GPU's texture unit does the bilinear
 * interpolation, and with mipmaps, the minification filtering plain
 * procedural noise has no equivalent of -- a hash lattice has no mip chain to
 * fall back on when a screen pixel spans many lattice cells, which is most of
 * what read as noise "grain" at distance and grazing angles.
 *
 * The 2D texture backs valueNoise(); the 3D texture backs valueNoise3() (used
 * by billow3's cauliflower erosion). Both are generated with the exact
 * hash12/hash13 formulas noise.glsl used to compute inline, evaluated once per
 * integer lattice point, so the baked field is the same statistical noise --
 * just precomputed instead of re-hashed every sample.
 */
public class CloudNoise {

    public static final int SIZE_2D = 256;
    public static final int SIZE_3D = 48;

    private static int texture2D;
    private static int texture3D;
    private static boolean initialized;

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        texture2D = build2D();
        texture3D = build3D();
    }

    public static int texture2D() {
        return texture2D;
    }

    public static int texture3D() {
        return texture3D;
    }

    private static int build2D() {
        ByteBuffer data = MemoryUtil.memAlloc(SIZE_2D * SIZE_2D);
        for (int j = 0; j < SIZE_2D; j++) {
            for (int i = 0; i < SIZE_2D; i++) {
                data.put((byte) Math.round(hash12(i, j) * 255f));
            }
        }
        data.flip();

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, SIZE_2D, SIZE_2D, 0, GL_RED, GL_UNSIGNED_BYTE, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(data);
        return tex;
    }

    private static int build3D() {
        ByteBuffer data = MemoryUtil.memAlloc(SIZE_3D * SIZE_3D * SIZE_3D);
        for (int k = 0; k < SIZE_3D; k++) {
            for (int j = 0; j < SIZE_3D; j++) {
                for (int i = 0; i < SIZE_3D; i++) {
                    data.put((byte) Math.round(hash13(i, j, k) * 255f));
                }
            }
        }
        data.flip();

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, tex);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, SIZE_3D, SIZE_3D, SIZE_3D, 0, GL_RED, GL_UNSIGNED_BYTE, data);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
        glGenerateMipmap(GL_TEXTURE_3D);
        glBindTexture(GL_TEXTURE_3D, 0);
        MemoryUtil.memFree(data);
        return tex;
    }

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    /** Mirrors noise.glsl's hash12, evaluated at integer lattice points. */
    private static float hash12(float px, float py) {
        float ax = fract(px * 0.1031f);
        float ay = fract(py * 0.1031f);
        float az = ax; // p.xyx repeats p.x into the third component
        float d = ax * (ay + 33.33f) + ay * (az + 33.33f) + az * (ax + 33.33f);
        ax += d;
        ay += d;
        az += d;
        return fract((ax + ay) * az);
    }

    /** Mirrors noise.glsl's hash13, evaluated at integer lattice points. */
    private static float hash13(float px, float py, float pz) {
        float qx = fract(px * 0.1031f);
        float qy = fract(py * 0.1031f);
        float qz = fract(pz * 0.1031f);
        float d = qx * (qz + 31.32f) + qy * (qy + 31.32f) + qz * (qx + 31.32f);
        qx += d;
        qy += d;
        qz += d;
        return fract((qx + qy) * qz);
    }

    public static void cleanup() {
        if (texture2D != 0) {
            glDeleteTextures(texture2D);
            texture2D = 0;
        }
        if (texture3D != 0) {
            glDeleteTextures(texture3D);
            texture3D = 0;
        }
        initialized = false;
    }
}
