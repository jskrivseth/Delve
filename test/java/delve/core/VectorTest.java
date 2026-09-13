package delve.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the package-private maths of {@link Vector}. */
public class VectorTest {

    private static final float EPS = 1e-6f;

    private void assertVecEquals(Vector expected, Vector actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }

    @Test
    public void testCopyConstructorIsIndependent() {
        Vector orig = new Vector(1, 2, 3);
        Vector copy = new Vector(orig);
        copy.add(new Vector(5, -5, 0));
        assertVecEquals(new Vector(1, 2, 3), orig);
    }

    @Test
    public void testMatchesEuclideanNorm() {
        Vector v = new Vector(1, 2, 2);
        assertEquals(3f, v.magnitude(), 1e-6, "magnitude");
    }

    @Test
    public void testMagnitudeSquaredConsistent() {
        Vector v = new Vector(2, 3, -6);
        assertEquals(v.magnitude() * v.magnitude(), v.magnitudeSquared(), 1e-6, "square matches dot(v,v)");
        assertEquals(49f, v.magnitudeSquared(), 1e-6, "explicit 4+9+36");
    }

    @Test
    public void testAddSubCompose() {
        Vector a = new Vector(1, 2, 3);
        Vector b = new Vector(4, -1, 2);
        a.add(b);
        assertEquals(5, a.x, EPS);
        assertEquals(1, a.y, EPS);
        assertEquals(5, a.z, EPS);
        a.sub(b);
        assertVecEquals(new Vector(1, 2, 3), a);
    }

    @Test
    public void testScaleHandlesSignedFractions() {
        Vector a = new Vector(2, -4, 8);
        a.scale(-0.5f);
        assertEquals(-1f, a.x, EPS);
        assertEquals(2f, a.y, EPS);
        assertEquals(-4f, a.z, EPS);
    }

    @Test
    public void testDotSymmetricAndProjectionAware() {
        Vector u = new Vector(1, 2, 3);
        Vector v = new Vector(4, 5, 6);
        assertEquals(u.dot(v), v.dot(u), 1e-6, "symmetric");
        Vector i = new Vector(1, 0, 0);
        Vector j = new Vector(0, 1, 0);
        assertEquals(0, i.dot(j), 1e-6, "orthogonal basis vectors");
        assertEquals(1f, u.dot(i), 1e-6, "x-component extraction");
    }

    @Test
    public void testCrossProducesNormalOfParallelPlane() {
        Vector cross = Vector.cross(new Vector(2, 0, 0), new Vector(0, 3, 0));
        assertEquals(0f, cross.x, EPS);
        assertEquals(0f, cross.y, EPS);
        assertEquals(6f, cross.z, EPS, "parallelogram area along normal");
    }

    @Test
    public void testNormalizeReachesUnitLength() {
        Vector a = new Vector(1, 2, 2);
        a.normalize();
        assertEquals(1f, a.magnitude(), 1e-6, "unit-length result");
        assertEquals(1f / 3f, a.x, 1e-6, "consistent scaling on x");
        assertEquals(2f / 3f, a.y, 1e-6, "consistent scaling on y");
    }

    @Test
    public void testNormalizedKeepsZeroUnchanged() {
        Vector r = new Vector(0, 0, 0).normalized();
        assertEquals(0, r.x, EPS);
        assertEquals(0, r.y, EPS);
        assertEquals(0, r.z, EPS);
    }

    @Test
    public void testPlusMinusAreImmutableSideEffects() {
        Vector a = new Vector(1, 2, 3);
        Vector b = new Vector(4, -1, 2);
        Vector s = a.plus(b);
        assertVecEquals(new Vector(5, 1, 5), s);
        assertVecEquals(new Vector(1, 2, 3), a);
        assertVecEquals(new Vector(4, -1, 2), b);
    }

    @Test
    public void testScaledRespectsSign() {
        Vector s = new Vector(1, 2, 3).scaled(-1f);
        assertEquals(-1f, s.x, EPS);
        assertEquals(-2f, s.y, EPS);
        assertEquals(-3f, s.z, EPS);
    }

    @Test
    public void testRotatedAboutZByQuadrantSwapsAxes() {
        Vector r = Vector.axisRotation(new Vector(1, 0, 0), new Vector(0, 0, 1f), (float) (Math.PI / 2));
        assertEquals(1f, r.magnitude(), 1e-4, "radius preserved under quadrant rotation");
        assertEquals(0f, r.z, 1e-4, "no component pushed out of the XY plane");
    }

    @Test
    public void testInvertedZFlipsOnlyThirdComponent() {
        Vector v = new Vector(1, 2, 3).invertedZ();
        assertEquals(1f, v.x, EPS);
        assertEquals(2f, v.y, EPS);
        assertEquals(-3f, v.z, EPS);
    }
}
