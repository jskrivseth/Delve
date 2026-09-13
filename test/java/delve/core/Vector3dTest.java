package delve.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Vector3d}. */
public class Vector3dTest {

    private static final double EPS = 1e-6;

    private void assertVecEquals(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }

    @Test
    public void testCopyConstructorIsIndependent() {
        Vector3d orig = new Vector3d(1, 2, 3);
        Vector3d copy = new Vector3d(orig);
        copy.add(new Vector3d(5, -5, 0));
        assertVecEquals(new Vector3d(1, 2, 3), orig);
    }

    @Test
    public void testMagnitudeMatchesEuclideanNorm() {
        Vector3d v = new Vector3d(1, 2, 2);
        assertEquals(3.0, v.magnitude(), 1e-9, "magnitude");
    }

    @Test
    public void testMagnitudeSquaredConsistency() {
        Vector3d v = new Vector3d(2, 3, -6);
        double ms = v.magnitudeSquared();
        assertEquals(v.magnitude() * v.magnitude(), ms, 1e-9, "magnitude² == dot(v,v)");
        assertEquals(49.0, ms, 1e-9, "explicit 4+9+36");
    }

    @Test
    public void testAddSubCompose() {
        Vector3d a = new Vector3d(1, 2, 3);
        Vector3d b = new Vector3d(4, -1, 2);
        a.add(b);
        assertEquals(5, a.x, EPS);
        assertEquals(1, a.y, EPS);
        assertEquals(5, a.z, EPS);
        a.sub(b);
        assertVecEquals(new Vector3d(1, 2, 3), a);
    }

    @Test
    public void testScaleNegZeroFractions() {
        Vector3d a = new Vector3d(2, -4, 8);
        a.scale(-0.5);
        assertEquals(-1, a.x, EPS);
        assertEquals(2, a.y, EPS);
        assertEquals(-4, a.z, EPS);
    }

    @Test
    public void testDotProductSymmetryAndGeometricMeaning() {
        Vector3d u = new Vector3d(1, 2, 3);
        Vector3d v = new Vector3d(4, 5, 6);
        assertEquals(u.dot(v), v.dot(u), 1e-9, "symmetric");
        Vector3d i = new Vector3d(1, 0, 0);
        Vector3d j = new Vector3d(0, 1, 0);
        assertEquals(0, i.dot(j), 1e-9, "basis vectors are orthogonal");
        assertEquals(1, u.dot(new Vector3d(1, 0, 0)), 1e-9, "projection component");
    }

    @Test
    public void testCrossOrthogonalAndAreaScalarComponent() {
        Vector3d cross = Vector3d.cross(new Vector3d(2, 0, 0), new Vector3d(0, 3, 0));
        assertEquals(0, cross.x, EPS);
        assertEquals(0, cross.y, EPS);
        assertEquals(6, cross.z, EPS, "area of parallelogram formed by basis-aligned edges");
    }

    @Test
    public void testNormalizeLeavesNonUnitVectorsScaledToOne() {
        Vector3d a = new Vector3d(1, 2, 2);
        a.normalize();
        assertEquals(1.0, a.magnitude(), 1e-9, "unit-length after normalization");
        assertEquals(1.0 / 3.0, a.x, 1e-9, "scaling factor preserved on x");
        assertEquals(2.0 / 3.0, a.y, 1e-9, "scaling factor preserved on y");
        assertEquals(2.0 / 3.0, a.z, 1e-9, "scaling factor preserved on z");
    }

    @Test
    public void testPlusMinusAreSideEffectFree() {
        Vector3d a = new Vector3d(1, 2, 3);
        Vector3d b = new Vector3d(4, -1, 2);
        Vector3d s = a.plus(b);
        assertVecEquals(new Vector3d(5, 1, 5), s);
        assertVecEquals(new Vector3d(1, 2, 3), a);
        assertVecEquals(new Vector3d(4, -1, 2), b);
    }

    @Test
    public void testScaledRespectsSign() {
        Vector3d s = new Vector3d(1, 2, 3).scaled(-1);
        assertEquals(-1, s.x, EPS);
        assertEquals(-2, s.y, EPS);
        assertEquals(-3, s.z, EPS);
    }

    @Test
    public void testNormalizedHandlesZeroByKeepingItNull() {
        Vector3d zero = new Vector3d(0, 0, 0);
        Vector3d r = zero.normalized();
        assertEquals(0, r.x, EPS);
        assertEquals(0, r.y, EPS);
        assertEquals(0, r.z, EPS);
    }

    @Test
    public void testRotatedAboutArbitraryAxisPersistsWithinRadius() {
        Vector3d rotated = Vector3d.axisRotation(new Vector3d(1, 0, 0), new Vector3d(0, 0, 1), Math.PI / 2);
        assertEquals(0, rotated.x, 1e-3, "rotated onto y");
        assertEquals(1, rotated.y, 1e-3, "positive hemisphere chosen for +90deg orientation");
        assertEquals(0, rotated.z, 1e-3);
        assertEquals(1.0, rotated.magnitude(), 1e-3, "rotation preserves radius");
    }

    @Test
    public void testInverseZKeepsComponents() {
        Vector3d v = new Vector3d(1, 2, 3).invertedZ();
        assertEquals(1, v.x, EPS);
        assertEquals(2, v.y, EPS);
        assertEquals(-3, v.z, EPS);
    }
}

