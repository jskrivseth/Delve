/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import static cydi.WorldChunk.sizeX;
import static cydi.WorldChunk.sizeY;
import static cydi.WorldChunk.sizeZ;
import java.util.ArrayList;
import static org.lwjgl.opengl.GL11.*;

/**
 *
 * @author Jesse
 */
public class BlockFinder {

    private static final float ARM_LENGTH = 6;
    public static ArrayList<Vector3d> pickerRay = new ArrayList<Vector3d>();

    public static void setBlockType(int x, int y, int z, int type) {
        int chunkX = Math.floorDiv(x, WorldChunk.sizeX);
        int chunkZ = Math.floorDiv(z, WorldChunk.sizeZ);
        WorldChunk chunk = World.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            Game.consoleMsg("No chunk found @ (" + chunkX + "," + chunkZ + "} using (" + x + "," + y + "," + z + ")");
            return;
        }
        x -= chunk.worldPosX;
        z -= chunk.worldPosY;
        if (x >= 0 && x < WorldChunk.sizeX) {
            if (y >= 0 && y < WorldChunk.sizeY) {
                if (z >= 0 && z < WorldChunk.sizeZ) {
                    World.BLOCK_LOCK.writeLock().lock();
                    try {
                        chunk.blocks[x][y][z] = type;
                    } finally {
                        World.BLOCK_LOCK.writeLock().unlock();
                    }
                    chunk.meshIsStale = true;
                    chunk.isModified = true;
                    if (type != Block.AIR) {
                        chunk.noteBlockPlacedAt(y);
                    }
                    invalidateSeam(chunkX, chunkZ, x, z);
                    return;
                }
            }
        }
        Game.consoleMsg("Failed to set a block @ (" + chunkX + "," + chunkZ + "} using (" + x + "," + y + "," + z + ")");
    }

    /**
     * Rebuilds the neighbouring chunk when a block on a shared border changes.
     *
     * A block face is emitted by the chunk that owns the block, so editing a block
     * on a chunk edge changes which faces the *neighbour* must draw. Without this
     * the seam keeps its stale mesh and you can see straight through the world.
     */
    private static void invalidateSeam(int chunkX, int chunkZ, int localX, int localZ) {
        if (localX == 0) {
            markMeshStale(chunkX - 1, chunkZ);
        } else if (localX == WorldChunk.sizeX - 1) {
            markMeshStale(chunkX + 1, chunkZ);
        }
        if (localZ == 0) {
            markMeshStale(chunkX, chunkZ - 1);
        } else if (localZ == WorldChunk.sizeZ - 1) {
            markMeshStale(chunkX, chunkZ + 1);
        }
    }

    private static void markMeshStale(int chunkX, int chunkZ) {
        WorldChunk neighbor = World.getChunk(chunkX, chunkZ);
        if (neighbor != null) {
            neighbor.meshIsStale = true;
        }
    }

    public static void setSelectedBlock(int x, int y, int z) {
        int chunkX = Math.floorDiv(x, WorldChunk.sizeX);
        int chunkZ = Math.floorDiv(z, WorldChunk.sizeZ);
        WorldChunk chunk = World.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            Game.consoleMsg("No chunk found @ (" + chunkX + "," + chunkZ + "} using (" + x + "," + y + "," + z + ")");
            return;
        }
        x -= chunk.worldPosX;
        z -= chunk.worldPosY;
        if (x >= 0 && x < WorldChunk.sizeX) {
            if (y >= 0 && y < WorldChunk.sizeY) {
                if (z >= 0 && z < WorldChunk.sizeZ) {
                    chunk.selectedBlock = new Block(x, y, z);
                    return;
                }
            }
        }
        Game.consoleMsg("Failed to set a block @ (" + chunkX + "," + chunkZ + "} using (" + x + "," + y + "," + z + ")");
    }

    public static void setBlockType(WorldChunk chunk, int x, int y, int z, int type) {
        if (x >= 0 && x < WorldChunk.sizeX) {
            if (y >= 0 && y < WorldChunk.sizeY) {
                if (z >= 0 && z < WorldChunk.sizeZ) {
                    World.BLOCK_LOCK.writeLock().lock();
                    try {
                        chunk.blocks[x][y][z] = type;
                    } finally {
                        World.BLOCK_LOCK.writeLock().unlock();
                    }
                    chunk.meshIsStale = true;
                    chunk.isModified = true;
                    if (type != Block.AIR) {
                        chunk.noteBlockPlacedAt(y);
                    }
                    invalidateSeam(chunk.posX, chunk.posY, x, z);
                    return;
                }
            }
        }
        Game.consoleMsg("Failed to set a block @ (" + chunk.worldPosX + "," + chunk.worldPosY + "} using (" + x + "," + y + "," + z + ")");
    }

    /**
     * A voxel targeted by the view ray.
     *
     * Carries both the block that was hit and the empty cell in front of the face
     * that was entered through. Placement used to re-march the ray to work that
     * out, with a different implementation and different chunk math, so the two
     * could disagree about what the player was looking at.
     */
    public static final class RayHit {

        public final int x, y, z;
        public final int placeX, placeY, placeZ;

        RayHit(int x, int y, int z, int placeX, int placeY, int placeZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.placeX = placeX;
            this.placeY = placeY;
            this.placeZ = placeZ;
        }

        public Block block() {
            return new Block(x, y, z);
        }
    }

    /** Supplies block types in world coordinates. */
    public interface BlockLookup {
        int typeAt(int x, int y, int z);
    }

    /** Blocks the ray stops on. Water is see-through so a lake bed stays reachable. */
    private static boolean isTargetable(int type) {
        return type != Block.AIR && type != Block.WATER;
    }

    /**
     * Walks a ray through the voxel grid and returns the first targetable block,
     * or null when nothing is within {@code maxDistance}.
     *
     * This is the Amanatides and Woo traversal: it jumps straight to the next
     * voxel boundary along whichever axis is nearest, so it visits each voxel the
     * ray actually crosses exactly once. The previous approach swept three
     * separate axis planes and took the nearest of the three results, which
     * contributed nothing for any axis the ray ran parallel to.
     *
     * Kept free of world and camera state so it can be exercised directly against
     * a synthetic grid.
     */
    public static RayHit raycast(double ox, double oy, double oz,
                                 double dirX, double dirY, double dirZ,
                                 double maxDistance, BlockLookup lookup) {
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (len == 0) {
            return null;
        }
        double dx = dirX / len, dy = dirY / len, dz = dirZ / len;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        // Distance along the ray between successive boundaries on each axis, and
        // the distance to the first one. An axis the ray does not move along never
        // becomes the nearest boundary, so it is parked at infinity.
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = boundaryDistance(ox, x, stepX, dx);
        double tMaxY = boundaryDistance(oy, y, stepY, dy);
        double tMaxZ = boundaryDistance(oz, z, stepZ, dz);

        int prevX = x, prevY = y, prevZ = z;
        double travelled = 0;

        while (travelled <= maxDistance) {
            if (Game.DEBUG_DRAW_CAMERA_RAY) {
                pickerRay.add(new Vector3d(x + 0.5, y + 0.5, z + 0.5));
            }
            if (isTargetable(lookup.typeAt(x, y, z))) {
                return new RayHit(x, y, z, prevX, prevY, prevZ);
            }
            prevX = x;
            prevY = y;
            prevZ = z;

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                travelled = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                travelled = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                travelled = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    /**
     * Walks the view ray and returns the first targetable block, or null when
     * nothing is in reach.
     */
    public static RayHit pickTargetedBlock() {
        Vector3d position = Game.GAME_CAMERA.getPosition();
        Vector3d sight = Game.GAME_CAMERA.getSight();

        if (Game.DEBUG_DRAW_CAMERA_RAY) {
            pickerRay.clear();
        }

        World.BLOCK_LOCK.readLock().lock();
        try {
            return raycast(position.x, position.y, position.z,
                    sight.x, sight.y, sight.z, ARM_LENGTH, BlockFinder::blockAt);
        } finally {
            World.BLOCK_LOCK.readLock().unlock();
        }
    }

    /** Distance along the ray from the origin to the first voxel boundary. */
    private static double boundaryDistance(double origin, int cell, int step, double direction) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? cell + 1 : cell;
        return (boundary - origin) / direction;
    }

    /**
     * Block type at world coordinates, or air when the chunk is not loaded.
     * The caller holds the read lock.
     */
    private static int blockAt(int worldX, int worldY, int worldZ) {
        if (worldY < 0 || worldY >= WorldChunk.sizeY) {
            return Block.AIR;
        }
        WorldChunk chunk = World.getChunk(
                Math.floorDiv(worldX, WorldChunk.sizeX),
                Math.floorDiv(worldZ, WorldChunk.sizeZ));
        if (chunk == null || !chunk.isGenerated) {
            return Block.AIR;
        }
        int[][][] data = chunk.blocks;
        if (data == null) {
            return Block.AIR;
        }
        return data[Math.floorMod(worldX, WorldChunk.sizeX)][worldY][Math.floorMod(worldZ, WorldChunk.sizeZ)];
    }
}
