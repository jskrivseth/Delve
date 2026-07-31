/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import org.joml.Vector3f;
import java.util.Arrays;

//First Person Camera Controller
public class FirstPersonCamera extends Camera {

    boolean onGround = false;
    static Vector3d inputVector = new Vector3d();

    //Constructor that takes the starting x, y, z location of the camera
    public FirstPersonCamera(float x, float y, float z) {
        //instantiate position Vector3f to the x y z params.
        position = new Vector3d(x, y, z);
        velocity = new Vector3d(0, 0, 0);
        acceleration = new Vector3d(0, 0, 0);
    }

    /** Restores a saved orientation, rebuilding the derived sight vectors. */
    public void setOrientation(float newYaw, float newPitch) {
        this.yaw = 0;
        this.pitch = 0;
        this.sight = new Vector3d(0, 0, -1);
        this.right = new Vector3d(-1, 0, 0);
        yaw(newYaw);
        pitch(newPitch);
    }

    public void resetPosition() {
        position.y = Game.PLAYER_START_POSITION.y;
        position.y = Game.PLAYER_START_POSITION.y;
        position.z = Game.PLAYER_START_POSITION.z;
        velocity = new Vector3d(0, 0, 0);
        yaw = 120.0f;
        pitch = 0.0f;
    }

//increment the camera's current yaw rotation
    public void yaw(float amount) {
        //increment the yaw by the amount param
//        if (yaw > 360.0f || yaw < -360.0f) {
//            yaw = 0.0f;
//        }
        yaw += amount;
        sight = cydi.Vector3d.axisRotation(sight, sky, (float)(amount * DEG_TO_RAD));
        right = cydi.Vector3d.cross(sight, sky).normalized();
        orientationChanged();
    }

//increment the camera's current pitch rotation
    public void pitch(float amount) {
        //increment the pitch by the amount param
        if (amount + pitch < 85.0f && amount + pitch > -85.0f) {
            pitch += amount;
            sight = cydi.Vector3d.axisRotation(sight, right, (float)(amount * DEG_TO_RAD));
            orientationChanged();
        }
    }

    //moves the camera forward relative to its current rotation (yaw)
    public void walkForward(float distance) {
//        applyAcceleration(new Vector3f(
//                distance * (float) Math.sin(Math.toRadians(yaw)),
//                -distance * (float) Math.tan(Math.toRadians(pitch)),
//                -distance * (float) Math.cos(Math.toRadians(yaw))));
        inputVector.x = distance * (float) Math.sin(Math.toRadians(yaw));
        inputVector.y = 0;
        inputVector.z = -distance * (float) Math.cos(Math.toRadians(yaw));
        applyAcceleration(inputVector);
    }

//moves the camera backward relative to its current rotation (yaw)
    public void walkBackwards(float distance) {
//        applyAcceleration(new Vector3f(
//                -distance * (float) Math.sin(Math.toRadians(yaw)),
//                distance * (float) Math.tan(Math.toRadians(pitch)),
//                distance * (float) Math.cos(Math.toRadians(yaw))));
        inputVector.x = -distance * (float) Math.sin(Math.toRadians(yaw));
        inputVector.y = 0;
        inputVector.z = distance * (float) Math.cos(Math.toRadians(yaw));
        applyAcceleration(inputVector);
    }

    public void flyUp(float distance) {
        inputVector.x = 0;
        inputVector.y = distance;
        inputVector.z = 0;
        applyAcceleration(inputVector);
    }

    public void fallDown(float distance) {
        inputVector.x = 0;
        inputVector.y = -distance;
        inputVector.z = 0;
        applyAcceleration(inputVector);
    }

//strafes the camera left relitive to its current rotation (yaw)
    public void strafeLeft(float distance) {
        inputVector.x = distance * (float) Math.sin(Math.toRadians(yaw - 90));
        inputVector.y = 0;
        inputVector.z = -distance * (float) Math.cos(Math.toRadians(yaw - 90));
        applyAcceleration(inputVector);
    }

//strafes the camera right relitive to its current rotation (yaw)
    public void strafeRight(float distance) {
        inputVector.x = distance * (float) Math.sin(Math.toRadians(yaw + 90));
        inputVector.y = 0;
        inputVector.z = -distance * (float) Math.cos(Math.toRadians(yaw + 90));
        applyAcceleration(inputVector);
    }

    //translates and rotate the matrix so that it looks through the camera
    //this does basically what gluLookAt() used to do, but into a JOML matrix
    @Override
    public void lookThrough() {
        rebuildViewMatrix();
    }

    /**
     * Rebuilds the projection matrices for the given framebuffer size.
     */
    public void setup(int width, int height) {
        // The draw distance is a square of chunks, so the far plane has to reach the
        // square's diagonal (~1.42x) or the plane slices visible chunks in half.
        CAMERA_FAR_PLANE = (Game.OPT_DRAW_DISTANCE + 1) * WorldChunk.sizeX * 1.5f;
        if (CAMERA_FAR_PLANE < CAMERA_NEAR_PLANE + 1.0f) {
            CAMERA_FAR_PLANE = CAMERA_NEAR_PLANE + 1.0f;
        }
        float aspect = height == 0 ? 1.0f : (float) width / (float) height;

        perspectiveProjectionMatrix.identity()
                .perspective(CAMERA_FOV * DEG_TO_RAD, aspect, CAMERA_NEAR_PLANE, CAMERA_FAR_PLANE);

        orthographicProjectionMatrix.identity()
                .ortho(0.0f, width, height, 0.0f, -1.0f, 1.0f);

        rebuildViewMatrix();
    }

    public void update() {
        applyGravity();
        applyVelocity();
    }

    /** Half width of the player box, and how far the eye sits above the feet. */
    private static final float HALF_WIDTH = 0.3f;
    private static final float EYE_HEIGHT = 1.62f;
    private static final float HEAD_ROOM = 0.18f;
    /** Movement is split into steps so fast flight cannot tunnel through blocks. */
    private static final double MAX_STEP = 0.4;

    @Override
    public void applyGravity() {
        // Fly mode is a free-cam: gravity would otherwise fight every ascent.
        if (Game.GAME_FLYMODE) {
            return;
        }
        applyAcceleration(new Vector3d(0, -CAMERA_GRAVITY * Game.GAME_TIME, 0));
    }

    /**
     * Moves the player box through the voxel grid, resolving one axis at a time.
     *
     * This replaces the old approach of sampling the single block under the camera
     * and clamping to six precomputed planes, which only ever considered one voxel
     * and so let the player clip diagonally past block corners.
     */
    @Override
    public void applyVelocity() {
        double dx = velocity.x;
        double dy = velocity.y;
        double dz = velocity.z;

        if (Game.OPT_BLOCK_COLLISION) {
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(distance / MAX_STEP));
            // Take the read lock once for the whole move rather than re-acquiring it
            // for every axis of every sub-step.
            World.BLOCK_LOCK.readLock().lock();
            try {
                unstick();
                for (int s = 0; s < steps; s++) {
                    moveAxis(dx / steps, 0, 0);
                    moveAxis(0, 0, dz / steps);
                    moveAxis(0, dy / steps, 0);
                }
            } finally {
                World.BLOCK_LOCK.readLock().unlock();
            }
        } else {
            position.x += dx;
            position.y += dy;
            position.z += dz;
            onGround = false;
        }

        velocity.x /= this.CAMERA_DRAG;
        velocity.y /= (this.CAMERA_DRAG - 0.05f);
        velocity.z /= this.CAMERA_DRAG;
        positionChanged();
    }

    /**
     * Lifts the player out of solid ground.
     *
     * Spawning happens before the surrounding chunks exist, and terrain can also
     * generate around a player already standing there, so without this the player
     * ends up sealed in and has to dig out. The caller holds the read lock.
     */
    private void unstick() {
        if (!intersectsWorld()) {
            return;
        }
        double startY = position.y;
        for (int i = 0; i < WorldChunk.sizeY; i++) {
            position.y += 1.0;
            if (!intersectsWorld()) {
                velocity.x = 0;
                velocity.y = 0;
                velocity.z = 0;
                onGround = true;
                return;
            }
        }
        // Nowhere clear above; leave the player where they were rather than
        // teleporting them to the sky.
        position.y = startY;
    }

    private void moveAxis(double dx, double dy, double dz) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        position.x += dx;
        position.y += dy;
        position.z += dz;

        if (!intersectsWorld()) {
            if (dy != 0) {
                onGround = false;
            }
            return;
        }

        // Blocked: undo this axis and kill the velocity that drove into the wall.
        position.x -= dx;
        position.y -= dy;
        position.z -= dz;

        if (dx != 0) {
            velocity.x = 0;
        }
        if (dz != 0) {
            velocity.z = 0;
        }
        if (dy != 0) {
            if (dy < 0) {
                onGround = true;
                if (Game.GAME_FLYMODE) {
                    Game.GAME_FLYMODE = false;
                }
            }
            velocity.y = 0;
        }
    }

    /**
     * True when the player box overlaps any solid voxel.
     *
     * The caller already holds the read lock. The AABB spans at most two chunks,
     * so the chunk handle is cached across the scan instead of being looked up per
     * voxel.
     */
    private boolean intersectsWorld() {
        double minX = position.x - HALF_WIDTH;
        double maxX = position.x + HALF_WIDTH;
        double minY = position.y - EYE_HEIGHT;
        double maxY = position.y + HEAD_ROOM;
        double minZ = position.z - HALF_WIDTH;
        double maxZ = position.z + HALF_WIDTH;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        if (y1 < 0) {
            return true;
        }
        y0 = Math.max(y0, 0);
        y1 = Math.min(y1, WorldChunk.sizeY - 1);

        WorldChunk cached = null;
        int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;

        for (int x = x0; x <= x1; x++) {
            int cx = Math.floorDiv(x, WorldChunk.sizeX);
            int lx = Math.floorMod(x, WorldChunk.sizeX);
            for (int z = z0; z <= z1; z++) {
                int cz = Math.floorDiv(z, WorldChunk.sizeZ);
                int lz = Math.floorMod(z, WorldChunk.sizeZ);

                if (cached == null || cx != cachedCx || cz != cachedCz) {
                    cached = World.getChunk(cx, cz);
                    cachedCx = cx;
                    cachedCz = cz;
                }
                if (cached == null || !cached.isGenerated) {
                    continue;   // never trap the player inside unloaded terrain
                }
                int[][][] data = cached.blocks;
                if (data == null) {
                    continue;
                }
                for (int y = y0; y <= y1; y++) {
                    int type = data[lx][y][lz];
                    if (type != Block.AIR && type != Block.WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void applyAcceleration(Vector3d velocity) {
        MathHelper.divide(velocity, this.CAMERA_MASS, acceleration);
        Vector3d.add(this.velocity, acceleration, this.velocity);
    }

}
