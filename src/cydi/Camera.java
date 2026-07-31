/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * Camera base holding the projection/view matrices for the programmable pipeline.
 * All fixed-function matrix state (glMatrixMode/glLoadIdentity/gluPerspective)
 * has been replaced by JOML matrices uploaded as shader uniforms.
 */
public abstract class Camera {

    protected static final float DEG_TO_RAD = (float) Math.PI / 180.f;

    public Vector3d acceleration = null;
    public Vector3d velocity = null;
    //3d vector to store the camera's position in
    public Vector3d position = null;

    /**
     * The view or sight of this Camera, as a normalized Vector relative to
     * this Camera's position.
     *
     * @see #position
     */
    protected Vector3d sight = new Vector3d(0, 0, -1);

    protected Vector3d right = new Vector3d(0, 0, -1);
    protected static final Vector3d sky = new Vector3d(0, -1, 0);

    //the rotation around the Y axis of the camera
    protected float yaw = 0.0f;
    //the rotation around the X axis of the camera
    protected float pitch = 0.0f;

    public static float CAMERA_FAR_PLANE = 512.0f;
    public static float CAMERA_FOV = 75.0f;
    public static float CAMERA_NEAR_PLANE = 0.1f;
    public static float CAMERA_MASS = 10.0f;
    public static float CAMERA_DRAG = 1.075f;
    public static float CAMERA_GRAVITY = 0.005f;

    protected static final Matrix4f perspectiveProjectionMatrix = new Matrix4f();
    protected static final Matrix4f orthographicProjectionMatrix = new Matrix4f();
    protected static final Matrix4f viewMatrix = new Matrix4f();

    /** Combined projection*view, recomputed whenever the camera moves or turns. */
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final FrustumIntersection frustum = new FrustumIntersection();

    //xLower, xUpper, yLower, yUpper, zLower, zUpper
    public static float[] CAMERA_BOUNDS = new float[]{
        0, (World.sizeX * WorldChunk.sizeX) - 1, 0, WorldChunk.sizeY - 1, 0, (World.sizeY * WorldChunk.sizeZ) - 1
    };
    public static Vector3d CAMERA_POSITION;

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public static Matrix4f getProjectionMatrix() {
        return perspectiveProjectionMatrix;
    }

    public static Matrix4f getOrthographicMatrix() {
        return orthographicProjectionMatrix;
    }

    public static Matrix4f getViewMatrix() {
        return viewMatrix;
    }

    public void update() {
        throw new RuntimeException("Not Implemented");
    }

    public void applyGravity() {
        throw new RuntimeException("Not Implemented");
    }

    public void applyAcceleration(Vector3d vector) {
        throw new RuntimeException("Not Implemented");
    }

    public void applyVelocity() {
        throw new RuntimeException("Not Implemented");
    }

    protected void positionChanged() {
        rebuildViewMatrix();
    }

    protected void orientationChanged() {
        rebuildViewMatrix();
    }

    /**
     * Rebuilds the view matrix and the culling frustum.
     *
     * The world is drawn camera-relative on X/Z (each chunk's model matrix already
     * subtracts the camera position), so the view matrix only translates on Y.
     */
    protected void rebuildViewMatrix() {
        viewMatrix.identity()
                .rotateX(pitch * DEG_TO_RAD)
                .rotateY(yaw * DEG_TO_RAD)
                .translate(0.0f, position == null ? 0.0f : -(float) position.y, 0.0f);

        perspectiveProjectionMatrix.mul(viewMatrix, viewProjection);
        frustum.set(viewProjection);
    }

    /**
     * Frustum test in the same camera-relative space the chunks are drawn in.
     */
    public static boolean isBoxVisible(float minX, float minY, float minZ,
                                       float maxX, float maxY, float maxZ) {
        return frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    Vector3d getPosition() {
        return new Vector3d(position);
    }

    Vector3d getSight() {
        return new Vector3d(sight);
    }

    public void lookThrough() {
        rebuildViewMatrix();
    }

    protected void applyBounds() {
        throw new RuntimeException("Not Implemented");
    }
}
