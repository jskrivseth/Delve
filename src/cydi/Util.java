/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.util.*;

/**
 *
 * @author Jesse
 */
public class Util {

    public static FloatBuffer getFloatBuffer(float[] values) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(values.length);
        buffer.put(values);
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer getByteBuffer(byte[] values) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(values.length);
        buffer.put(values);
        buffer.flip();
        return buffer;
    }

    public static FloatBuffer getFloatBuffer(List<Vector3f> values) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(values.size() * 3);
        for (Vector3f vector : values) {
            buffer.put(vector.x);
            buffer.put(vector.y);
            buffer.put(vector.z);
        }
        buffer.flip();
        return buffer;
    }

    public static FloatBuffer getFloatBuffer(int size) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(size);
        for (int i = 0; i < size; i++) {
            buffer.put(0.0f);
        }
        buffer.flip();
        return buffer;
    }

    public static IntBuffer getIntBuffer(int size) {
        IntBuffer buffer = BufferUtils.createIntBuffer(size);
        for (int i = 0; i < size; i++) {
            buffer.put(0);
        }
        buffer.flip();
        return buffer;
    }

    public static IntBuffer getIntBuffer(int[] values) {
        IntBuffer buffer = BufferUtils.createIntBuffer(values.length);
        buffer.put(values);
        buffer.flip();
        return buffer;
    }

    public static String getApplicationPath() {
        File directory = new File(".");
        return directory.getAbsolutePath().substring(0, directory.getAbsolutePath().length() - 1);
    }

    public static long getMaxMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory();
    }

    public static long getAvailableMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    public static long getAvailableHeapSize() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.freeMemory();
    }

    public static double logb(double a, double b) {
        return Math.log(a) / Math.log(b);
    }
}
