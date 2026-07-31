/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import java.io.Serializable;
import java.util.*;
import java.io.*;

import static org.lwjgl.opengl.GL11.GL_LINES;

/**
 *
 * @author Jesse
 */
public class WorldChunk implements Serializable, Block.SolidityLookup {

    /*
     * Handles
     */
    public transient int vboVertexHandle;
    public transient int vaoHandle;
    private transient Matrix4f modelMatrix = new Matrix4f();

    /*
     * Locks
     */
    public Object generateLock = new Object();
    public Object buildMeshLock = new Object();
    public Object drawLock = new Object();
    /*
     * Flags
     */
    public boolean meshIsStale = false;
    public boolean vboIsStale = false;
    public boolean isRefreshing = false;
    public boolean isDefunct = false;
    public boolean isBuilding = false;
    public boolean isBuilt = false;
    public boolean isGenerating = false;
    public boolean isGenerated = false;
    public boolean isZombie = false;
    public boolean neighborsGenerated = false;
    public boolean purgeVBO = false;
    public boolean serialize = false;
    /** Set when the player edits this chunk, so it is persisted before unloading. */
    public volatile boolean isModified = false;
    /** Highest occupied Y + 1, used to skip the empty air above the terrain. */
    private volatile int maxHeight = sizeY;

    /** Full daylight level; light falls by one per block spread. */
    public static final int MAX_LIGHT = 15;
    /**
     * Sky light per voxel, flattened as (x * sizeY + y) * sizeZ + z.
     *
     * A byte array rather than int[][][] because this is rebuilt on every mesh
     * pass and touched once per voxel.
     */
    private volatile byte[] skyLight;
    /** Scratch BFS queue, reused across rebuilds to avoid per-pass allocation. */
    private transient int[] lightQueue;
    private boolean modelsSnappedToGround = false;
    /*
     * State
     */
    public Block selectedBlock = null;
    
    /*
     * Data
     */
    public volatile FloatBuffer vbuffer;
    public volatile int[][][] blocks;     //Contains all the blocks in this chunk
    public volatile int numVerts;
    /** Vertices in the leading opaque range; the remainder is translucent. */
    public volatile int opaqueVerts;
    private volatile int pendingOpaqueVerts;
    /**
     * Vertex count for the mesh sitting in {@link #vbuffer}, published to
     * {@link #numVerts} only once that data is actually uploaded. Assigning
     * numVerts from the builder thread would let the render thread draw the
     * previous VBO with the new count and read past the end of it.
     */
    private volatile int pendingVerts;
    /*
     * Properties
     */
    public int posX;  //Describes this WorldChunk's position in the World
    public int posY;  //Describes this WorldChunk's position in the World
    public static int sizeX = 16;
    public static int sizeY = 128;
    public static int sizeZ = 16;
    public int worldPosX;
    public int worldPosY;
    private transient float[][] bbox;
    /*
     * Stats
     */
    public transient int BLOCK_COUNT = 0;
    public transient int FACE_COUNT = 0;
    private transient boolean wireframe = Game.OPT_DRAW_WIRES;
    private transient boolean[] EXPOSED_FACES = new boolean[6];
    ;
    private static final float ARM_LENGTH = 5;

    public WorldChunk(int x, int y) {
        blocks = new int[sizeX][sizeY][sizeZ];
        posX = x;
        posY = y;
        worldPosX = (int) posX * sizeX;
        worldPosY = (int) posY * sizeZ;
        //models = new ArrayList<GLModel>();
        //this.loadModels();
    }

    public int getBlockType(Block block) {        //Game.consoleMsg("Looking for a block in " + block.x + "," + block.y + "," + block.z + " = " + blocks[block.x][block.y][block.z]);
        return blocks[block.x][block.y][block.z];
    }

    public int getBlockType(int x, int y, int z) {            //Game.consoleMsg("Looking for a block in " + block.x + "," + block.y + "," + block.z + " = " + blocks[block.x][block.y][block.z]);
        return blocks[x][y][z];
    }

    public static WorldChunk getCurrentChunk() {
        int x = (int) Math.floor(Game.GAME_CAMERA.position.x) / WorldChunk.sizeX;
        int y = (int) Math.floor(Game.GAME_CAMERA.position.z) / WorldChunk.sizeZ;
        return World.getChunk(x, y);
    }


    public void generate() {
        if (this.isGenerating) {
            System.out.println("ERROR: attempt to generate a block already being generated");
            return;
        }
        this.isGenerating = true;

        // A previously edited chunk is restored from disk instead of being
        // regenerated, otherwise the player's changes vanish when it reloads.
        if (this.load()) {
            recomputeMaxHeight();
            this.isModified = true;
            this.isGenerating = false;
            this.isGenerated = true;
            this.isBuilt = false;
            return;
        }

        int[][][] data = new int[sizeX][sizeY][sizeZ];
        int highest = 1;

        //Do a 2D perlin noise for the surface
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                float xPos = (worldPosX + x) / (float) (128.0f);
                float zPos = (worldPosY + z) / (float) (128.0f);
                double v = PerlinNoiseGenerator.getNoise(xPos, zPos, 3, 3.25f, sizeY);
                v += 1.0f;

                int height = (int) (v * (sizeY / 2.0));
                if (height > sizeY - 4) {
                    height = sizeY - 4;
                }
                if (height < 1) {
                    height = 1;
                }

                int surface = surfaceTypeFor(height);
                for (int y = 0; y <= height; y++) {
                    int type;
                    if (y == 0) {
                        type = Block.BEDROCK;
                    } else if (y == height) {
                        type = surface;
                    } else if (y >= height - 3) {
                        type = (surface == Block.SAND) ? Block.SAND : Block.DIRT;
                    } else {
                        type = Block.STONE;
                    }
                    data[x][y][z] = type;
                }

                // Flood everything below sea level so lakes get a flat surface.
                for (int y = height + 1; y <= SEA_LEVEL; y++) {
                    data[x][y][z] = Block.WATER;
                }

                int top = Math.max(height, height < SEA_LEVEL ? SEA_LEVEL : height);
                if (top + 1 > highest) {
                    highest = top + 1;
                }
            }
        }

        this.blocks = data;
        this.maxHeight = Math.min(highest + 1, sizeY);

        this.isGenerating = false;
        this.isGenerated = true;
        this.isBuilt = false;
    }

    /** Sea level; columns below this are flooded with water. */
    public static final int SEA_LEVEL = 32;

    private static int surfaceTypeFor(int height) {
        if (height < SEA_LEVEL) {
            return Block.SAND;      // lake bed, covered by water above
        }
        if (height <= SEA_LEVEL + 2) {
            return Block.SAND;      // beach
        }
        if (height >= 96) {
            return Block.SNOW;
        }
        return Block.GRASS;
    }

    /** Recomputes the highest occupied Y so meshing can skip the air above it. */
    private void recomputeMaxHeight() {
        int highest = 1;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = sizeY - 1; y >= 0; y--) {
                    if (blocks[x][y][z] != 0) {
                        if (y + 1 > highest) {
                            highest = y + 1;
                        }
                        break;
                    }
                }
            }
        }
        this.maxHeight = Math.min(highest + 1, sizeY);
    }

    /** Raises the meshing ceiling when a block is placed above the old top. */
    public void noteBlockPlacedAt(int y) {
        if (y + 2 > this.maxHeight) {
            this.maxHeight = Math.min(y + 2, sizeY);
        }
    }

    /**
     * Solidity in chunk-local coordinates, following the neighbouring chunk when
     * the lookup crosses a border. Used for ambient occlusion.
     */
    @Override
    public boolean isSolid(int x, int y, int z) {
        if (y < 0) {
            return true;
        }
        if (y >= sizeY) {
            return false;
        }
        if (x >= 0 && x < sizeX && z >= 0 && z < sizeZ) {
            return !Block.isTransparent(blocks[x][y][z]);
        }
        return World.isSolidGlobal(worldPosX + x, y, worldPosY + z);
    }

    private static int lightIndex(int x, int y, int z) {
        return (x * sizeY + y) * sizeZ + z;
    }

    /** Sky light at a chunk-local voxel, crossing into neighbours when needed. */
    @Override
    public int lightAt(int x, int y, int z) {
        if (y < 0) {
            return 0;
        }
        if (y >= sizeY) {
            return MAX_LIGHT;
        }
        if (x >= 0 && x < sizeX && z >= 0 && z < sizeZ) {
            byte[] light = this.skyLight;
            return light == null ? MAX_LIGHT : light[lightIndex(x, y, z)];
        }
        return World.skyLightGlobal(worldPosX + x, y, worldPosY + z);
    }

    /** Raw local sky light, with no neighbour lookup. Returns -1 when unlit. */
    int localLight(int x, int y, int z) {
        byte[] light = this.skyLight;
        if (light == null || y < 0 || y >= sizeY || x < 0 || x >= sizeX || z < 0 || z >= sizeZ) {
            return -1;
        }
        return light[lightIndex(x, y, z)];
    }

    /**
     * Floods sky light through the chunk.
     *
     * Columns open to the sky start at full strength and carry it straight down
     * unattenuated; light then spreads outward losing one level per block, which
     * is what makes a tunnel fall off with depth. Borders are seeded from already
     * lit neighbours so light bleeds between chunks.
     */
    private void computeSkyLight() {
        byte[] light = this.skyLight;
        if (light == null) {
            light = new byte[sizeX * sizeY * sizeZ];
            this.skyLight = light;
        } else {
            java.util.Arrays.fill(light, (byte) 0);
        }
        if (lightQueue == null) {
            lightQueue = new int[sizeX * sizeY * sizeZ];
        }

        int head = 0, tail = 0;

        // Seed straight down from open sky.
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = sizeY - 1; y >= 0; y--) {
                    if (!Block.transmitsLight(blocks[x][y][z])) {
                        break;
                    }
                    light[lightIndex(x, y, z)] = (byte) MAX_LIGHT;
                    lightQueue[tail++] = (x << 11) | (y << 4) | z;
                }
            }
        }

        // Seed the four borders from neighbouring chunks that are already lit.
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                tail = seedBorder(light, tail, x, y, 0, worldPosX + x, y, worldPosY - 1);
                tail = seedBorder(light, tail, x, y, sizeZ - 1, worldPosX + x, y, worldPosY + sizeZ);
            }
            for (int z = 0; z < sizeZ; z++) {
                tail = seedBorder(light, tail, 0, y, z, worldPosX - 1, y, worldPosY + z);
                tail = seedBorder(light, tail, sizeX - 1, y, z, worldPosX + sizeX, y, worldPosY + z);
            }
        }

        while (head < tail) {
            int packed = lightQueue[head++];
            int x = (packed >> 11) & 0x1F;
            int y = (packed >> 4) & 0x7F;
            int z = packed & 0xF;
            int level = light[lightIndex(x, y, z)];
            if (level <= 1) {
                continue;
            }
            tail = spread(light, tail, x + 1, y, z, level);
            tail = spread(light, tail, x - 1, y, z, level);
            tail = spread(light, tail, x, y + 1, z, level);
            tail = spread(light, tail, x, y - 1, z, level);
            tail = spread(light, tail, x, y, z + 1, level);
            tail = spread(light, tail, x, y, z - 1, level);

            if (tail >= lightQueue.length - 8) {
                break;  // saturated; remaining spread would be negligible
            }
        }
    }

    private int seedBorder(byte[] light, int tail, int x, int y, int z,
                           int worldX, int worldY, int worldZ) {
        if (!Block.transmitsLight(blocks[x][y][z])) {
            return tail;
        }
        int neighbor = World.skyLightGlobal(worldX, worldY, worldZ);
        int level = neighbor - 1;
        int idx = lightIndex(x, y, z);
        if (level > light[idx]) {
            light[idx] = (byte) level;
            lightQueue[tail++] = (x << 11) | (y << 4) | z;
        }
        return tail;
    }

    private int spread(byte[] light, int tail, int x, int y, int z, int level) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return tail;
        }
        if (!Block.transmitsLight(blocks[x][y][z])) {
            return tail;
        }
        int idx = lightIndex(x, y, z);
        int next = level - Block.lightCost(blocks[x][y][z]);
        if (next > light[idx]) {
            light[idx] = (byte) next;
            if (tail < lightQueue.length) {
                lightQueue[tail++] = (x << 11) | (y << 4) | z;
            }
        }
        return tail;
    }

    public boolean isReady() {
        return (this.vboVertexHandle != 0 || this.vboIsStale);
    }

    public void buildMesh() {

        this.neighborsGenerated = World.allNeighborsAreGenerated(this);
        if (!this.neighborsGenerated) {
            //System.out.println("Can't build yet because the neighbors aren't ready");
            return;
        }
        if (this.isBuilding) {
            Game.consoleMsg("Attempt to build a mesh for a chunk that is already building.. ");
            return;
        }
        if (!this.isGenerated) {
            Game.consoleMsg("Attempt to build a mesh for a chunk that is not generated.. ");
            return;
        }
        this.isBuilding = true;
        if (this.EXPOSED_FACES == null) {
            this.EXPOSED_FACES = new boolean[6];
        }
        BLOCK_COUNT = 0;
        FACE_COUNT = 0;

        // Voxel data is read here on a worker thread while the main thread may be
        // editing blocks, so both passes run under the shared read lock.
        World.BLOCK_LOCK.readLock().lock();
        try {
            computeSkyLight();
            int ceiling = Math.min(this.maxHeight, sizeY);

            // Pass 1: count exposed faces so the chunk buffer can be sized exactly.
            // Nothing is allocated per block, unlike the old FloatBuffer-per-cube
            // approach that then had to be merged into a second buffer.
            int faceCount = 0;
            int blockCount = 0;
            for (int i = 0; i < sizeX; i++) {
                for (int j = 0; j < ceiling; j++) {
                    for (int k = 0; k < sizeZ; k++) {
                        if (blocks[i][j][k] != 0) {
                            faceCount += computeExposedFaces(i, j, k, EXPOSED_FACES);
                            blockCount++;
                        }
                    }
                }
            }

            BLOCK_COUNT = blockCount;
            FACE_COUNT = faceCount;

            if (faceCount == 0) {
                this.pendingVerts = 0;
                this.vbuffer = null;
                this.isBuilding = false;
                this.isBuilt = true;
                this.vboIsStale = true;
                return;
            }

            // Pass 2: opaque faces first, then translucent, so the two occupy
            // contiguous ranges of one buffer and can be drawn as separate passes.
            FloatBuffer buffer = BufferUtils.createFloatBuffer(faceCount * Block.FLOATS_PER_FACE);
            for (int i = 0; i < sizeX; i++) {
                for (int j = 0; j < ceiling; j++) {
                    for (int k = 0; k < sizeZ; k++) {
                        int type = blocks[i][j][k];
                        if (type != 0 && !Block.isTranslucent(type)
                                && computeExposedFaces(i, j, k, EXPOSED_FACES) > 0) {
                            Block.writeCube(buffer, i, j, k, EXPOSED_FACES, type, this);
                        }
                    }
                }
            }
            this.pendingOpaqueVerts = buffer.position() / Renderer.FLOATS_PER_VERTEX;

            for (int i = 0; i < sizeX; i++) {
                for (int j = 0; j < ceiling; j++) {
                    for (int k = 0; k < sizeZ; k++) {
                        int type = blocks[i][j][k];
                        if (type != 0 && Block.isTranslucent(type)
                                && computeExposedFaces(i, j, k, EXPOSED_FACES) > 0) {
                            Block.writeCube(buffer, i, j, k, EXPOSED_FACES, type, this);
                        }
                    }
                }
            }

            this.pendingVerts = buffer.position() / Renderer.FLOATS_PER_VERTEX;
            buffer.flip();
            this.vbuffer = buffer;
        } finally {
            World.BLOCK_LOCK.readLock().unlock();
        }

        this.isBuilding = false;
        this.isBuilt = true;
        this.vboIsStale = true;
    }

    /**
     * Fills {@code out} with the exposure mask for one block and returns how many
     * faces are exposed. Neighbouring chunks are consulted at the chunk borders.
     */
    private int computeExposedFaces(int i, int j, int k, boolean[] out) {
        int type = blocks[i][j][k];
        int neighborX = 0, neighborY = 0;
        if (i == 0) {  // Look down
            neighborX = World.chunkNeighbor(2, i, j, k, posX, posY);
        } else if (i == sizeX - 1) { //Look up
            neighborX = World.chunkNeighbor(1, i, j, k, posX, posY);
        }
        if (k == 0) { // Look left
            neighborY = World.chunkNeighbor(3, i, j, k, posX, posY);
        } else if (k == sizeZ - 1) {  //Look right
            neighborY = World.chunkNeighbor(4, i, j, k, posX, posY);
        }

        out[0] = (k == sizeZ - 1) ? showsFace(type, neighborY) : showsFace(type, blocks[i][j][k + 1]);   //Front  +z
        out[1] = (i == sizeX - 1) ? showsFace(type, neighborX) : showsFace(type, blocks[i + 1][j][k]);   //Right  +x
        out[2] = (j == sizeY - 1) || showsFace(type, blocks[i][j + 1][k]);                               //Top    +y
        out[3] = (i == 0) ? showsFace(type, neighborX) : showsFace(type, blocks[i - 1][j][k]);           //Left   -x
        out[4] = (j > 0) && showsFace(type, blocks[i][j - 1][k]);                                        //Bottom -y
        out[5] = (k == 0) ? showsFace(type, neighborY) : showsFace(type, blocks[i][j][k - 1]);           //Back   -z

        int count = 0;
        for (int f = 0; f < 6; f++) {
            if (out[f]) {
                count++;
            }
        }
        return count;
    }

    /**
     * A face is drawn when its neighbour does not fully occlude it. Two adjacent
     * blocks of the same see-through type (glass against glass) hide the shared
     * face so the interior of a pane or a tree canopy is not meshed.
     */
    private static boolean showsFace(int type, int neighborType) {
        return Block.isTransparent(neighborType) && neighborType != type;
    }

    public void buildVBO() {
        FloatBuffer data = this.vbuffer;
        if (data == null) {
            // Mesh built to nothing (fully enclosed or empty chunk).
            this.numVerts = 0;
            this.vboIsStale = false;
            return;
        }
        Renderer.uploadChunkMesh(this, data);
        // Only now is the count valid for the buffer the GPU holds.
        this.numVerts = this.pendingVerts;
        this.opaqueVerts = this.pendingOpaqueVerts;
        this.vboIsStale = false;
        this.vbuffer = null;
    }

    public void drawMesh() {
        if (this.numVerts <= 0 || this.vaoHandle == 0) {
            return;
        }

        Renderer.renderChunkMesh(this);

        if (this.selectedBlock != null) {
            drawSelectionBox();
        }
        if (Game.OPT_DRAW_WIRES) {
            drawBoundingBox();
        }
    }

    /** Wireframe cube around the block currently under the crosshair. */
    private void drawSelectionBox() {
        Vector b = Block.openGLCoordinatesForBlock(this.selectedBlock);
        float x = b.x, y = b.y, z = b.z;

        float[] v = {
            // bottom loop
            x, y, z - 1,  x + 1, y, z - 1,
            x + 1, y, z - 1,  x + 1, y, z,
            x + 1, y, z,  x, y, z,
            x, y, z,  x, y, z - 1,
            // top loop
            x, y + 1, z - 1,  x + 1, y + 1, z - 1,
            x + 1, y + 1, z - 1,  x + 1, y + 1, z,
            x + 1, y + 1, z,  x, y + 1, z,
            x, y + 1, z,  x, y + 1, z - 1,
            // verticals
            x, y, z - 1,  x, y + 1, z - 1,
            x + 1, y, z - 1,  x + 1, y + 1, z - 1,
            x + 1, y, z,  x + 1, y + 1, z,
            x, y, z,  x, y + 1, z,
        };

        Renderer.drawDebugGeometry(GL_LINES, v, v.length / 3, chunkModelMatrix(), 1f, 1f, 1f, 1f);
    }

    /** Model matrix placing this chunk in camera-relative world space. */
    private Matrix4f chunkModelMatrix() {
        return modelMatrix.identity().translate(
                (float) (this.worldPosX - Game.GAME_CAMERA.position.x),
                0f,
                (float) (this.worldPosY - Game.GAME_CAMERA.position.z));
    }

    public void render() {
        if (this.purgeVBO) {
            deleteVBO();
            return;
        }
        if (this.vboIsStale) {
            this.vboIsStale = false;
            this.buildVBO();
        }
        if (this.meshIsStale) {
            this.meshIsStale = false;
            this.refreshMesh();
        }
        if (this.vaoHandle != 0) {
            drawMesh();
        }
        Game.BLOCK_COUNT += BLOCK_COUNT;
        Game.FACE_COUNT += FACE_COUNT;
    }

    private void drawBoundingBox() {
        float w = sizeX;
        float h = sizeY;
        float d = sizeZ;

        float[] v = {
            0, 0, 0,  w, 0, 0,   w, 0, 0,  w, 0, d,
            w, 0, d,  0, 0, d,   0, 0, d,  0, 0, 0,
            0, h, 0,  w, h, 0,   w, h, 0,  w, h, d,
            w, h, d,  0, h, d,   0, h, d,  0, h, 0,
            0, 0, 0,  0, h, 0,   w, 0, 0,  w, h, 0,
            w, 0, d,  w, h, d,   0, 0, d,  0, h, d,
        };

        Renderer.drawDebugGeometry(GL_LINES, v, v.length / 3, chunkModelMatrix(), 1f, 0f, 0f, 1f);
    }

    /**
     * Frustum test against the chunk's camera-relative bounding box.
     */
    public boolean isVisible() {
        if (!Game.FRUSTUM_CULLING) {
            return true;
        }
        float minX = (float) (this.worldPosX - Game.GAME_CAMERA.position.x);
        float minZ = (float) (this.worldPosY - Game.GAME_CAMERA.position.z);
        return Camera.isBoxVisible(minX, 0f, minZ, minX + sizeX, sizeY, minZ + sizeZ);
    }

    public void deleteVBO() {
        Renderer.deleteChunkMesh(this);
        this.numVerts = 0;
    }

    public void refreshMesh() {
        this.isRefreshing = true;
        // Reuse the shared pool rather than spawning a raw Thread per rebuild.
        World.threadPool.execute(new WorldChunkBufferBuilderThread(this));
    }

    public void rebuildNeighborVBOs() {
        //get all neighbors in x +/-, y +/-
        //foreach neighbor, delete their display list
        if (this.posX > 0 && World.getChunk(this.posX - 1, this.posY) != null) {
            World.getChunk(this.posX - 1, this.posY).refreshMesh();
        }
        if (this.posX < World.sizeX - 1 && World.getChunk(this.posX + 1, this.posY) != null) {
            World.getChunk(this.posX + 1, this.posY).refreshMesh();
        }
        if (this.posY > 0 && World.getChunk(this.posX, this.posY - 1) != null) {
            World.getChunk(this.posX, this.posY - 1).refreshMesh();
        }
        if (this.posY < World.sizeY - 1 && World.getChunk(this.posX, this.posY + 1) != null) {
            World.getChunk(this.posX, this.posY + 1).refreshMesh();
        };
    }

    public boolean serialize() {
        // Player edits must always persist, otherwise they are lost the moment the
        // chunk is swept and regenerated from noise.
        if (this.isModified || Game.OPT_SAVE_CHUNKS) {
            this.save();
        }
        this.purgeVBO = true;
        return true;
    }

    public boolean save() {
        World.BLOCK_LOCK.readLock().lock();
        try {
            return Serializer.serializeArray(this.blocks, saveName());
        } finally {
            World.BLOCK_LOCK.readLock().unlock();
        }
    }

    public boolean load() {
        int[][][] loaded = Serializer.deserializeArray(saveName());
        if (loaded == null) {
            return false;
        }
        this.blocks = loaded;
        return true;
    }

    private String saveName() {
        return this.posX + "-" + this.posY + ".chunk";
    }

    protected void finalize() throws Throwable {
        if (this.vboVertexHandle != 0 || this.vaoHandle != 0) {
            String error = "Attempt to run garbage collection on a chunk with an active VBO - the memory will leak!!";
            System.out.println(error);
            Game.consoleMsg(error);
        }
        this.vbuffer = null;
    }

    public String toString() {
        String ret = "";
        ret += ":x:" + this.posX;
        ret += ":y:" + this.posY;
        ret += ":is_generated:" + this.isGenerated;
        ret += ":is_generating:" + this.isGenerating;
        ret += ":is_refreshing:" + this.isRefreshing;
        ret += ":is_zombie:" + this.isZombie;
        ret += ":is_building:" + this.isBuilding;
        ret += ":is_built:" + this.isBuilt;
        ret += ":is_defunct:" + this.isDefunct;
        ret += ":is_ready::" + this.isReady();
        ret += ":is_visible:" + this.isVisible();
        ret += ":vbo:" + this.vboVertexHandle;
        ret += ":is_stale:" + this.vboIsStale;
        ret += ":is_mesh_stale:" + this.meshIsStale;
        return ret;
    }
}
