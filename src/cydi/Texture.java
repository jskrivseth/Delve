package cydi;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Texture loading via STB, replacing the removed Slick2D TextureLoader.
 */
public class Texture {

    private final int id;
    private final int width;
    private final int height;

    private Texture(int id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    /** Loads a texture from disk or the classpath, returning null when unavailable. */
    public static Texture loadOrNull(String location) {
        ByteBuffer encoded = null;
        try {
            encoded = readBytes(location);
            if (encoded == null) {
                return null;
            }
            return decode(encoded);
        } catch (Exception e) {
            System.err.println("Failed to load texture " + location + ": " + e.getMessage());
            return null;
        } finally {
            if (encoded != null) {
                memFree(encoded);
            }
        }
    }

    private static ByteBuffer readBytes(String location) throws IOException {
        Path path = Paths.get(location);
        byte[] bytes;
        if (Files.isReadable(path)) {
            bytes = Files.readAllBytes(path);
        } else {
            String resource = location.startsWith("/") ? location : "/" + location;
            try (InputStream in = Texture.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                bytes = in.readAllBytes();
            }
        }
        ByteBuffer buffer = memAlloc(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static Texture decode(ByteBuffer encoded) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(false);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
            if (pixels == null) {
                throw new RuntimeException("stb_image: " + STBImage.stbi_failure_reason());
            }

            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

            // Nearest filtering keeps the voxel/pixel-art look crisp. Mip levels are
            // capped because deep mips average whole atlas tiles together and bleed
            // neighbouring textures into each face.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 2);

            STBImage.stbi_image_free(pixels);
            glBindTexture(GL_TEXTURE_2D, 0);
            return new Texture(id, w.get(0), h.get(0));
        }
    }

    public void bind() {
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void cleanup() {
        glDeleteTextures(id);
    }
}
