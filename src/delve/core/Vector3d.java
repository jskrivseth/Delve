// Copyright 2012 Mitchell Kember. Subject to the MIT license.
package delve.core;

/**
 * Vector represents a three-dimensional (3D) vector. In particular, it provides
 * two of most operations, one mutating the current Vector and another returning
 * a new one (e.g. add/plus, sub/minus, scale/scaled). It also provides a
 * convenient method for axis rotations.
 *
 * @author Mitchell Kember
 * @since 08/12/2011
 */
public final class Vector3d {

    /**
     * The X component of this Vector.
     */
    public double x;
    /**
     * The Y component of this Vector.
     */
    public double y;
    /**
     * The Z component of this Vector.
     */
    public double z;

    /**
     * Creates a new Vector with the specified components.
     *
     * @param x the X component
     * @param y the Y component
     * @param z the Z component
     */
    public Vector3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Creates a new Vector with the specified components.
     *
     * @param x the X component
     * @param y the Y component
     * @param z the Z component
     */
    public Vector3d(Vector3d v) {
        this.x = v.x;
        this.y = v.y;
        this.z = v.z;
    }

    /**
     * Creates a new null (zero) Vector.
     */
    public Vector3d() {
        this(0, 0, 0);
    }

    /**
     * Calculates the magnitude (length) of this Vector.
     *
     * @return the length of this Vector
     */
    public double magnitude() {
        return Math.sqrt((x * x) + (y * y) + z * z);
    }

    /**
     * Calculates the magnitude (length) squared of this Vector. This is less
     * expensive to call than {@code magnitude}, because it skips the square
     * root operation. Use this when, for example, sorting by Vector length and
     * the actual magnitude is not required.
     *
     * @return the magnitude squared
     */
    public double magnitudeSquared() {
        return (x * x) + (y * y) + (z * z);
    }

    /**
     * Adds {@code vec} to this Vector by adding each component separately.
     *
     * @param vec the addend
     */
    public void add(Vector3d vec) {
        x += vec.x;
        y += vec.y;
        z += vec.z;
    }
    
    public static void add(Vector3d src, Vector3d scalar, Vector3d dst) {
        dst.x = src.x + scalar.x;
        dst.y = src.y + scalar.y;
        dst.z = src.z + scalar.z;
    }

    /**
     * Subtracts {@code vec} from this Vector by subtracting each component
     * separately.
     *
     * @param vec the subtrahend
     */
    public void sub(Vector3d vec) {
        x -= vec.x;
        y -= vec.y;
        z -= vec.z;
    }

    /**
     * Scales this vector by the scalar value {@code s} by multiplying each
     * component separately.
     *
     * @param s the multiplier
     */
    public void scale(double s) {
        x *= s;
        y *= s;
        z *= s;
    }

    /**
     * Calculates the dot product of this Vector with {@code vec}.
     *
     * @param vec another Vector
     * @return the dot product
     */
    public double dot(Vector3d vec) {
        return x * vec.x + y * vec.y + z * vec.z;
    }

    /**
     * Calculates the cross product of {@code u} and {@code v} and returns the
     * result in a new Vector.
     *
     * @param u a Vector
     * @param v another Vector
     * @return the cross product
     */
    public static Vector3d cross(Vector3d u, Vector3d v) {
        return new Vector3d(u.y * v.z - u.z * v.y,
                u.z * v.x - u.x * v.z,
                u.x * v.y - u.y * v.x);
    }

    /**
     * Normalizes this Vector (i.e., makes its magnitude equal to 1).
     */
    public void normalize() {
        double mag = magnitude();
        if (mag == 0) {
            return;
        }
        x /= mag;
        y /= mag;
        z /= mag;
    }

    /**
     * Calculates the sum of this Vector and {@code vec}. This does not modify
     * this Vector.
     *
     * @param vec the addend
     * @return the sum
     */
    public Vector3d plus(Vector3d vec) {
        return new Vector3d(x + vec.x, y + vec.y, z + vec.z);
    }

    /**
     * Calculates the difference of this Vector and {@code vec}. This does not
     * modify this Vector.
     *
     * @param vec the subtrahend
     * @return the difference
     */
    public Vector3d minus(Vector3d vec) {
        return new Vector3d(x - vec.x, y - vec.y, z - vec.z);
    }

    /**
     * Calculates this vector scaled by the scalar value {@code s}. This does
     * not modify this Vector.
     *
     * @param s the multiplier
     * @return the scaled Vector
     */
    public Vector3d scaled(double s) {
        return new Vector3d(x * s, y * s, z * s);
    }

    /**
     * Calculates the normalized version of this Vector. This does not modify
     * this Vector.
     *
     * @return the unit Vector
     */
    public Vector3d normalized() {
        double mag = magnitude();
        if (mag == 0) {
            return new Vector3d();
        }
        return new Vector3d(x / mag, y / mag, z / mag);
    }

    /**
     * Rotates {@code vec} about an arbitrary {@code axis} by {@code angle}
     * radians. This does not modify {@code vec}.
     *
     * @param vec the Vector to rotate
     * @param axis the arbitrary axis to rotate about
     * @param angle the angle in radians to rotate
     * @return the rotated Vector
     */
    public static Vector3d axisRotation(Vector3d vec, Vector3d axis, double angle) {
        Vector3d nAxis = axis.normalized();

        double c = Math.cos(angle);
        double s = Math.sin(angle);

        return new Vector3d(nAxis.x * (nAxis.x * vec.x + nAxis.y * vec.y + nAxis.z * vec.z) * (1.f - c) + vec.x * c + (-nAxis.z * vec.y + nAxis.y * vec.z) * s,
                nAxis.y * (nAxis.x * vec.x + nAxis.y * vec.y + nAxis.z * vec.z) * (1.f - c) + vec.y * c + (nAxis.z * vec.x - nAxis.x * vec.z) * s,
                nAxis.z * (nAxis.x * vec.x + nAxis.y * vec.y + nAxis.z * vec.z) * (1.f - c) + vec.z * c + (-nAxis.y * vec.x + nAxis.x * vec.y) * s);
    }

    /**
     * Returns a copy of this Vector with its Z-axis inverted. Useful for
     * converting between a left-handed system and a right-handed system.
     *
     * @return the Vector
     */
    public Vector3d invertedZ() {
        return new Vector3d(x, y, -z);
    }
}

