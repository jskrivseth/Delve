package delve.core; // probe-marker-abc

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Util} buffer shims and {@link Util#logb(double, double)}. */
public class UtilTest {

    private static void drain(FloatBuffer buf) {
        while (buf.hasRemaining()) buf.get();
    }

    @Test
    public void testFromArrayStoresEveryElementInOrder() {
        FloatBuffer b = Util.getFloatBuffer(new float[]{-1, 2.5f, 3});
        assertEquals(3, b.capacity());
        drain(b);
        b.rewind();
        assertEquals(-1f, b.get(), 1e-6);
        assertEquals(2.5f, b.get(), 1e-6);
        assertEquals(3f, b.get(), 1e-6);
    }

    @Test
    public void testIntArrayWrappingPreservesOrderAndCapacity() {
        IntBuffer b = Util.getIntBuffer(new int[]{5, -7, 9, 1});
        assertEquals(4, b.capacity());
        b.rewind();
        assertEquals(5, b.get());
        assertEquals(-7, b.get());
        assertEquals(9, b.get());
        assertEquals(1, b.get());
    }

    @Test
    public void testZeroedFloatBufferIsWritable() {
        FloatBuffer b = Util.getFloatBuffer(4);
        assertEquals(4, b.capacity());
        b.put(11f);      // index 0 via a relative put
        b.put(22f);      // index 1 via a relative put
        b.put(0, 7f);    // absolute overlay replacing index 0
        b.rewind();
        assertEquals(7f,  b.get(), 1e-6); // absolute overlay wins on index 0
        assertEquals(22f, b.get(), 1e-6); // index 1 keeps its relative write
        assertEquals(0f,  b.get(), 1e-6); // untouched slot 2 stays 0
        assertEquals(0f,  b.get(), 1e-6); // untouched slot 3 stays 0
    }

    @Test
    public void testZeroedIntBufferIsWritable() {
        IntBuffer b = Util.getIntBuffer(3);
        assertEquals(3, b.capacity());
        b.put(0, 42);
        b.rewind();
        assertEquals(42, b.get());
        assertEquals(0, b.get());
        assertEquals(0, b.get());
    }

    @Test
    public void testListThreeDimensionalVectorsInterleavesEachChannel() {
        List<Vector3f> pts = new ArrayList<>();
        pts.add(new Vector3f(1, 2, 3));
        pts.add(new Vector3f(4, 5, 6));
        FloatBuffer b = Util.getFloatBuffer(pts);
        assertEquals(6, b.capacity());
        b.rewind();
        for (float expected : new float[]{1, 2, 3, 4, 5, 6}) {
            assertEquals(expected, b.get(), 1e-6);
        }
    }

    @Test
    public void testBytesFlipMarksContentFullyReadable() {
        byte[] payload = new byte[]{10, 20, 30};
        ByteBuffer b = Util.getByteBuffer(payload);
        assertEquals(3, b.remaining());
        for (byte element : payload) {
            assertEquals(element, b.get());
        }
        assertEquals(0, b.remaining());
    }

    @Test
    public void testApplicationPathHasNoTrailingSeparatorAndExists() {
        String path = Util.getApplicationPath();
        assertFalse(path.endsWith("\\"), "backslash trimmed");
        assertFalse(path.endsWith("/"), "forward slash trimmed");
        assertTrue(new File(path).isDirectory(), "points at a directory");
    }

    @Test
    public void testLogReportsIntegerPowerRelationships() {
        assertEquals(3f, Util.logb(8, 2), 1e-6);
        assertEquals(10f, Util.logb(1024, 2), 1e-6);
        assertEquals(16f, Util.logb(65536, 2), 1e-6);
    }
}

