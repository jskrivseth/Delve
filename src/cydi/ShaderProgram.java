package cydi;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL11.GL_TRUE;

/**
 * Compiles, links and drives a GLSL program. Uniform locations are cached
 * because uniform lookups by name are relatively expensive per frame.
 */
public class ShaderProgram {

    private final int programId;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public ShaderProgram(String vertexResource, String fragmentResource) {
        programId = glCreateProgram();
        if (programId == 0) {
            throw new RuntimeException("Could not create shader program");
        }

        int vs = compile(loadResource(vertexResource), GL_VERTEX_SHADER, vertexResource);
        int fs = compile(loadResource(fragmentResource), GL_FRAGMENT_SHADER, fragmentResource);

        glAttachShader(programId, vs);
        glAttachShader(programId, fs);
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Shader link failed: " + glGetProgramInfoLog(programId, 2048));
        }

        glDetachShader(programId, vs);
        glDetachShader(programId, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);

        glValidateProgram(programId);
        if (glGetProgrami(programId, GL_VALIDATE_STATUS) == 0) {
            System.err.println("Shader validation warning: " + glGetProgramInfoLog(programId, 2048));
        }
    }

    private static int compile(String source, int type, String name) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Failed compiling " + name + ": " + glGetShaderInfoLog(id, 2048));
        }
        return id;
    }

    private static String loadResource(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Shader resource not found on classpath: " + path);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed reading shader " + path, e);
        }
    }

    private int location(String name) {
        return uniforms.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
    }

    public void bind() {
        glUseProgram(programId);
    }

    public static void unbind() {
        glUseProgram(0);
    }

    public void setMatrix4f(String name, Matrix4f value) {
        int loc = location(name);
        if (loc < 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            value.get(fb);
            glUniformMatrix4fv(loc, false, fb);
        }
    }

    public void setVector3f(String name, Vector3f value) {
        int loc = location(name);
        if (loc >= 0) {
            glUniform3f(loc, value.x, value.y, value.z);
        }
    }

    public void setVector3f(String name, float x, float y, float z) {
        int loc = location(name);
        if (loc >= 0) {
            glUniform3f(loc, x, y, z);
        }
    }

    public void setVector4f(String name, float x, float y, float z, float w) {
        int loc = location(name);
        if (loc >= 0) {
            glUniform4f(loc, x, y, z, w);
        }
    }

    public void setFloat(String name, float value) {
        int loc = location(name);
        if (loc >= 0) {
            glUniform1f(loc, value);
        }
    }

    public void setInt(String name, int value) {
        int loc = location(name);
        if (loc >= 0) {
            glUniform1i(loc, value);
        }
    }

    public void setBoolean(String name, boolean value) {
        setInt(name, value ? 1 : 0);
    }

    public void cleanup() {
        unbind();
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }
}
