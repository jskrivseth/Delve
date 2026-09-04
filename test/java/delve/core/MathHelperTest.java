package delve.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MathHelperTest {

    @Test
    public void testMultiplyVector3f() {
        org.joml.Vector3f src = new org.joml.Vector3f(1.0f, 2.0f, 3.0f);
        org.joml.Vector3f dest = new org.joml.Vector3f();
        MathHelper.multiply(src, 2.0f, dest);
        assertEquals(2.0f, dest.x, 0.0001f);
        assertEquals(4.0f, dest.y, 0.0001f);
        assertEquals(6.0f, dest.z, 0.0001f);
    }

    @Test
    public void testDivideVector3f() {
        org.joml.Vector3f src = new org.joml.Vector3f(10.0f, 20.0f, 30.0f);
        org.joml.Vector3f dest = new org.joml.Vector3f();
        MathHelper.divide(src, 2.0f, dest);
        assertEquals(5.0f, dest.x, 0.0001f);
        assertEquals(10.0f, dest.y, 0.0001f);
        assertEquals(15.0f, dest.z, 0.0001f);
    }

    @Test
    public void testMultiplyVector3d() {
        Vector3d src = new Vector3d(1.0, 2.0, 3.0);
        Vector3d dest = new Vector3d();
        MathHelper.multiply(src, 2.0, dest);
        assertEquals(2.0, dest.x, 0.0001);
        assertEquals(4.0, dest.y, 0.0001);
        assertEquals(6.0, dest.z, 0.0001);
    }

    @Test
    public void testDivideVector3d() {
        Vector3d src = new Vector3d(10.0, 20.0, 30.0);
        Vector3d dest = new Vector3d();
        MathHelper.divide(src, 2.0, dest);
        assertEquals(5.0, dest.x, 0.0001);
        assertEquals(10.0, dest.y, 0.0001);
        assertEquals(15.0, dest.z, 0.0001);
    }
}
