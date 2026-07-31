/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import java.io.Serializable;
import java.nio.FloatBuffer;

/**
 * Block geometry generation.
 *
 * Each exposed face contributes two triangles (6 vertices) built from 4 unique
 * corners, wound counter-clockwise when viewed from outside the cube so the mesh
 * can be drawn with one GL_TRIANGLES call and back-face culling enabled.
 *
 * Corners are kept distinct (rather than a flat 6-vertex template) so per-vertex
 * ambient occlusion can be evaluated from the surrounding blocks.
 */
public class Block implements Serializable {

    /** Supplies block solidity and light in chunk-local coordinates. */
    public interface SolidityLookup {
        boolean isSolid(int x, int y, int z);

        /** Sky light level 0..MAX_LIGHT at a chunk-local voxel. */
        int lightAt(int x, int y, int z);
    }

    public static float size = 0.5f;
    public static final boolean[] ALL_FACES = new boolean[]{true, true, true, true, true, true};

    public static float[][] blockColors = new float[][]{
        //0 - air
        new float[]{},
        //1 - Grass
        new float[]{0.35f, 0.72f, 0.28f, 1.0f},
        //2 - Water
        new float[]{0.20f, 0.42f, 0.85f, 1.0f},
        //3 - Sand
        new float[]{0.85f, 0.81f, 0.58f, 1.0f},
        //4 - Snow
        new float[]{0.94f, 0.94f, 0.96f, 1.0f},
        //5 - Stone
        new float[]{0.50f, 0.50f, 0.50f, 1.0f},
        //6 - Dirt
        new float[]{0.46f, 0.32f, 0.20f, 1.0f},
        //7 - Cobblestone
        new float[]{0.44f, 0.44f, 0.44f, 1.0f},
        //8 - Wood log
        new float[]{0.42f, 0.32f, 0.18f, 1.0f},
        //9 - Planks
        new float[]{0.71f, 0.57f, 0.35f, 1.0f},
        //10 - Leaves
        new float[]{0.25f, 0.55f, 0.20f, 1.0f},
        //11 - Gravel
        new float[]{0.55f, 0.52f, 0.51f, 1.0f},
        //12 - Brick
        new float[]{0.66f, 0.34f, 0.28f, 1.0f},
        //13 - Bedrock
        new float[]{0.22f, 0.22f, 0.22f, 1.0f},
        //14 - Glass
        new float[]{0.80f, 0.90f, 0.95f, 1.0f},};

    public static final int AIR = 0, GRASS = 1, WATER = 2, SAND = 3, SNOW = 4,
            STONE = 5, DIRT = 6, COBBLESTONE = 7, WOOD = 8, PLANKS = 9,
            LEAVES = 10, GRAVEL = 11, BRICK = 12, BEDROCK = 13, GLASS = 14;

    /** Human readable names, indexed by block type. */
    public static final String[] BLOCK_NAMES = {
        "Air", "Grass", "Water", "Sand", "Snow", "Stone", "Dirt", "Cobblestone",
        "Wood", "Planks", "Leaves", "Gravel", "Brick", "Bedrock", "Glass",
    };

    /** Types a player may place, in block-picker order. */
    public static final int[] PLACEABLE_TYPES = {
        GRASS, DIRT, STONE, COBBLESTONE, SAND, GRAVEL, WOOD, PLANKS,
        LEAVES, BRICK, GLASS, SNOW, WATER,
    };

    public static String nameOf(int type) {
        return (type >= 0 && type < BLOCK_NAMES.length) ? BLOCK_NAMES[type] : "Unknown";
    }

    /**
     * Atlas tile coordinates per block type, as
     * {topCol, topRow, sideCol, sideRow, bottomCol, bottomRow}.
     *
     * terrain.png is a 16x16 grid of tiles, so a face must sample only its own
     * tile. Emitting raw 0..1 UVs would stretch the whole atlas over every face.
     */
    private static final int[][] BLOCK_TILES = {
        {0, 0, 0, 0, 0, 0},          //0  air (unused)
        {0, 0, 3, 0, 2, 0},          //1  grass: grass top, grass side, dirt below
        {13, 12, 13, 12, 13, 12},    //2  water
        {2, 1, 2, 1, 2, 1},          //3  sand
        {2, 4, 4, 4, 2, 0},          //4  snow: snow top, snowy side, dirt below
        {1, 0, 1, 0, 1, 0},          //5  stone
        {2, 0, 2, 0, 2, 0},          //6  dirt
        {0, 1, 0, 1, 0, 1},          //7  cobblestone
        {5, 1, 4, 1, 5, 1},          //8  wood log: rings on the cut ends, bark on sides
        {4, 0, 4, 0, 4, 0},          //9  planks
        {4, 3, 4, 3, 4, 3},          //10 leaves
        {3, 1, 3, 1, 3, 1},          //11 gravel
        {7, 0, 7, 0, 7, 0},          //12 brick
        {1, 1, 1, 1, 1, 1},          //13 bedrock
        {1, 3, 1, 3, 1, 3},          //14 glass
    };

    /** Atlas tiles per row/column, exposed so the HUD can slice block icons. */
    public static final float ATLAS_TILE_COUNT = 16.0f;

    public static int sideTileCol(int type) {
        return BLOCK_TILES[type][2];
    }

    public static int sideTileRow(int type) {
        return BLOCK_TILES[type][3];
    }

    /** Types that do not fully occlude the neighbouring face. */
    public static boolean isTransparent(int type) {
        return type == AIR || type == LEAVES || type == GLASS || type == WATER;
    }

    /** Types drawn in the blended pass after all opaque geometry. */
    public static boolean isTranslucent(int type) {
        return type == WATER;
    }

    /** Whether sky light passes through this block at all. */
    public static boolean transmitsLight(int type) {
        return type == AIR || type == WATER || type == LEAVES || type == GLASS;
    }

    /**
     * Light levels consumed passing through a block. Water and foliage dim what
     * travels through them; air and glass are free.
     */
    public static int lightCost(int type) {
        if (type == WATER) {
            return 3;
        }
        if (type == LEAVES) {
            return 2;
        }
        return 1;
    }

    /** Tiles per atlas row/column. */
    private static final float ATLAS_TILES = 16.0f;
    /**
     * Half-texel inset. Without it, linear filtering and mipmapping sample across
     * tile borders and bleed neighbouring atlas entries into each face.
     */
    private static final float UV_INSET = 0.5f / 256.0f;

    /** Vertices emitted per exposed face (2 triangles). */
    public static final int VERTS_PER_FACE = 6;
    /** position(3) + normal(3) + color+ao(4) + texcoord(2) + skylight(1) */
    public static final int FLOATS_PER_VERTEX = 13;
    public static final int FLOATS_PER_FACE = VERTS_PER_FACE * FLOATS_PER_VERTEX;

    /** Face normals, ordered 0=+z 1=+x 2=+y 3=-x 4=-y 5=-z. */
    private static final int[][] FACE_NORMALS = {
        {0, 0, 1}, {1, 0, 0}, {0, 1, 0}, {-1, 0, 0}, {0, -1, 0}, {0, 0, -1},
    };

    /** Per face, 4 corners of {dx, dy, dz, u, v}. */
    private static final float[][][] FACE_CORNERS = {
        { // Front +z
            {1, 1, 1, 1, 0}, {0, 1, 1, 0, 0}, {0, 0, 1, 0, 1}, {1, 0, 1, 1, 1},
        },
        { // Right +x
            {1, 1, 1, 0, 0}, {1, 0, 1, 0, 1}, {1, 0, 0, 1, 1}, {1, 1, 0, 1, 0},
        },
        { // Top +y
            {1, 1, 1, 1, 1}, {1, 1, 0, 1, 0}, {0, 1, 0, 0, 0}, {0, 1, 1, 0, 1},
        },
        { // Left -x
            {0, 1, 1, 1, 0}, {0, 1, 0, 0, 0}, {0, 0, 0, 0, 1}, {0, 0, 1, 1, 1},
        },
        { // Bottom -y
            {0, 0, 0, 0, 1}, {1, 0, 0, 1, 1}, {1, 0, 1, 1, 0}, {0, 0, 1, 0, 0},
        },
        { // Back -z
            {1, 0, 0, 0, 1}, {0, 0, 0, 1, 1}, {0, 1, 0, 1, 0}, {1, 1, 0, 0, 0},
        },
    };

    /** Two triangles over the 4 corners. */
    private static final int[] CORNER_ORDER = {0, 1, 2, 2, 3, 0};
    final int x;
    final int y;
    final int z;

    Block(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    Block(double x, double y, double z) {
        this.x = (int) x;
        this.y = (int) y;
        this.z = (int) z;
    }

    static Vector openGLCoordinatesForBlock(Block block) {
        return new Vector(block.x, block.y, block.z + 1);
    }

    /** Number of exposed faces in the given mask. */
    public static int countFaces(boolean[] faces) {
        int count = 0;
        for (int i = 0; i < faces.length; i++) {
            if (faces[i]) {
                count++;
            }
        }
        return count;
    }

    /**
     * Deterministic per-block color jitter.
     *
     * Replaces Math.random(), which made meshes flicker on every rebuild and
     * contended on a shared Random across the chunk-builder threads.
     */
    private static float jitter(int x, int y, int z, int salt) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (salt * 374761393);
        h ^= (h >>> 13);
        h *= 1274126177;
        h ^= (h >>> 16);
        return ((h & 0xFFFF) / 65535.0f) * 0.08f;
    }

    /**
     * Classic voxel ambient occlusion: a corner darkens with the number of solid
     * blocks touching it. Two solid edge neighbours fully close the corner.
     */
    private static float aoLevel(boolean side1, boolean side2, boolean corner) {
        int level;
        if (side1 && side2) {
            level = 0;
        } else {
            level = 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
        }
        return 0.45f + 0.55f * (level / 3.0f);
    }

    /**
     * Writes the exposed faces of one block directly into the supplied buffer.
     *
     * No allocation occurs here, which keeps chunk meshing off the GC's back: the
     * caller owns a single buffer for the whole chunk.
     *
     * @param buffer destination, positioned at the write point
     * @param x      block X within the chunk
     * @param y      block Y within the chunk
     * @param z      block Z within the chunk
     * @param faces  6-element exposure mask
     * @param type   block type, used for the color palette and atlas tile
     * @param solid  neighbour lookup used for ambient occlusion
     */
    public static void writeCube(FloatBuffer buffer, int x, int y, int z,
                                 boolean[] faces, int type, SolidityLookup solid) {
        if (buffer == null || faces == null || faces.length == 0) {
            return;
        }

        float[] base = blockColors[type];
        float r = base[0] + jitter(x, y, z, 1);
        float g = base[1] + jitter(x, y, z, 2);
        float b = base[2] + jitter(x, y, z, 3);

        // Per-block brightness. Folded into the AO channel so it also varies the
        // textured blocks, where the palette color is ignored entirely.
        float shade = 0.93f + jitter(x, y, z, 7) * 1.75f;

        int[] tiles = BLOCK_TILES[type];

        for (int f = 0; f < faces.length && f < FACE_CORNERS.length; f++) {
            if (!faces[f]) {
                continue;
            }

            // Face 2 is the top and face 4 the bottom; everything else is a side.
            int tileCol, tileRow;
            if (f == 2) {
                tileCol = tiles[0];
                tileRow = tiles[1];
            } else if (f == 4) {
                tileCol = tiles[4];
                tileRow = tiles[5];
            } else {
                tileCol = tiles[2];
                tileRow = tiles[3];
            }
            float u0 = tileCol / ATLAS_TILES + UV_INSET;
            float u1 = (tileCol + 1) / ATLAS_TILES - UV_INSET;
            float v0 = tileRow / ATLAS_TILES + UV_INSET;
            float v1 = (tileRow + 1) / ATLAS_TILES - UV_INSET;

            int[] n = FACE_NORMALS[f];
            float[][] corners = FACE_CORNERS[f];

            // Light is sampled from the open voxel this face looks into, which is
            // what makes a face inside a tunnel darker than one at the entrance.
            float light = solid.lightAt(x + n[0], y + n[1], z + n[2]) / 15.0f;

            // The two axes lying in the face plane.
            int nAxis = (n[0] != 0) ? 0 : (n[1] != 0) ? 1 : 2;
            int t1 = (nAxis + 1) % 3;
            int t2 = (nAxis + 2) % 3;

            float ao0 = cornerAo(solid, corners[0], n, t1, t2, x, y, z) * shade;
            float ao1 = cornerAo(solid, corners[1], n, t1, t2, x, y, z) * shade;
            float ao2 = cornerAo(solid, corners[2], n, t1, t2, x, y, z) * shade;
            float ao3 = cornerAo(solid, corners[3], n, t1, t2, x, y, z) * shade;

            // Two triangles over the four corners: 0-1-2, 2-3-0.
            putVertex(buffer, corners[0], n, x, y, z, r, g, b, ao0, u0, u1, v0, v1, light);
            putVertex(buffer, corners[1], n, x, y, z, r, g, b, ao1, u0, u1, v0, v1, light);
            putVertex(buffer, corners[2], n, x, y, z, r, g, b, ao2, u0, u1, v0, v1, light);
            putVertex(buffer, corners[2], n, x, y, z, r, g, b, ao2, u0, u1, v0, v1, light);
            putVertex(buffer, corners[3], n, x, y, z, r, g, b, ao3, u0, u1, v0, v1, light);
            putVertex(buffer, corners[0], n, x, y, z, r, g, b, ao0, u0, u1, v0, v1, light);
        }
    }

    /**
     * Ambient occlusion for one face corner. Written with scalars rather than
     * offset arrays so meshing stays allocation free on the worker threads.
     */
    private static float cornerAo(SolidityLookup solid, float[] corner, int[] n,
                                  int t1, int t2, int x, int y, int z) {
        int s1 = (corner[t1] > 0.5f) ? 1 : -1;
        int s2 = (corner[t2] > 0.5f) ? 1 : -1;

        int a0 = (t1 == 0) ? s1 : 0, a1 = (t1 == 1) ? s1 : 0, a2 = (t1 == 2) ? s1 : 0;
        int b0 = (t2 == 0) ? s2 : 0, b1 = (t2 == 1) ? s2 : 0, b2 = (t2 == 2) ? s2 : 0;

        int nx = x + n[0], ny = y + n[1], nz = z + n[2];
        boolean side1 = solid.isSolid(nx + a0, ny + a1, nz + a2);
        boolean side2 = solid.isSolid(nx + b0, ny + b1, nz + b2);
        boolean diag = solid.isSolid(nx + a0 + b0, ny + a1 + b1, nz + a2 + b2);

        return aoLevel(side1, side2, diag);
    }

    private static void putVertex(FloatBuffer buffer, float[] corner, int[] n,
                                  int x, int y, int z,
                                  float r, float g, float b, float ao,
                                  float u0, float u1, float v0, float v1,
                                  float light) {
        buffer.put(x + corner[0]);
        buffer.put(y + corner[1]);
        buffer.put(z + corner[2]);
        buffer.put(n[0]);
        buffer.put(n[1]);
        buffer.put(n[2]);
        buffer.put(r);
        buffer.put(g);
        buffer.put(b);
        // Alpha carries ambient occlusion; cutout transparency comes from the
        // atlas texture, so the channel is free for shading.
        buffer.put(ao);
        buffer.put(corner[3] == 0.0f ? u0 : u1);
        buffer.put(corner[4] == 0.0f ? v0 : v1);
        buffer.put(light);
    }
}
