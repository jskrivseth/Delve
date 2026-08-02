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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        int vs = compile(Preprocessor.expand(vertexResource), GL_VERTEX_SHADER, vertexResource);
        int fs = compile(Preprocessor.expand(fragmentResource), GL_FRAGMENT_SHADER, fragmentResource);

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

    private static int compile(Preprocessor.Result unit, int type, String name) {
        int id = glCreateShader(type);
        glShaderSource(id, unit.source());
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Failed compiling " + name + ": "
                    + glGetShaderInfoLog(id, 2048) + unit.sourceLegend());
        }
        return id;
    }

    /**
     * Resolves {@code #include "/shaders/..."} directives, which GLSL itself has no
     * concept of. Shared cloud and noise code lives in one place instead of being
     * copied into every shader that needs it.
     *
     * <p>Each distinct file becomes a numbered GLSL source string via {@code #line}
     * directives, so compiler errors keep reporting positions against the file the
     * code was actually written in rather than against the concatenated result.
     * Repeated includes are skipped, giving {@code #pragma once} semantics.
     */
    private static final class Preprocessor {

        private static final Pattern INCLUDE =
                Pattern.compile("^\\s*#include\\s+[\"<]([^\">]+)[\">]\\s*$");

        record Result(String source, List<String> files) {
            /** Maps GLSL source-string numbers back to file names for error messages. */
            String sourceLegend() {
                if (files.size() < 2) {
                    return "";
                }
                StringBuilder sb = new StringBuilder("\n  (source strings: ");
                for (int i = 0; i < files.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(i).append('=').append(files.get(i));
                }
                return sb.append(')').toString();
            }
        }

        private final List<String> files = new ArrayList<>();
        private final Set<String> included = new HashSet<>();
        private final StringBuilder out = new StringBuilder();

        static Result expand(String path) {
            Preprocessor p = new Preprocessor();
            p.emit(path, p.idFor(path), new ArrayDeque<>());
            return new Result(p.out.toString(), p.files);
        }

        private int idFor(String path) {
            int existing = files.indexOf(path);
            if (existing >= 0) {
                return existing;
            }
            files.add(path);
            return files.size() - 1;
        }

        private void emit(String path, int id, Deque<String> stack) {
            if (stack.contains(path)) {
                throw new RuntimeException("Circular #include: " + String.join(" -> ", stack)
                        + " -> " + path);
            }
            stack.push(path);
            String[] lines = loadResource(path).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = INCLUDE.matcher(lines[i]);
                if (!m.matches()) {
                    out.append(lines[i]).append('\n');
                    continue;
                }
                String target = resolve(m.group(1), path);
                if (included.add(target)) {
                    int childId = idFor(target);
                    out.append("#line 1 ").append(childId).append('\n');
                    emit(target, childId, stack);
                }
                // Restore the parent's numbering for whatever follows the directive.
                out.append("#line ").append(i + 2).append(' ').append(id).append('\n');
            }
            stack.pop();
        }

        private static String resolve(String target, String fromPath) {
            if (target.startsWith("/")) {
                return target;
            }
            int slash = fromPath.lastIndexOf('/');
            return (slash < 0 ? "" : fromPath.substring(0, slash + 1)) + target;
        }
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
