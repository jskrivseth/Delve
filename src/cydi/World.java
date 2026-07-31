/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import static cydi.BlockFinder.pickerRay;
import static cydi.WorldChunk.sizeX;
import static cydi.WorldChunk.sizeZ;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.opengl.GL12;
// LWJGL vector imports replaced with JOML
import org.joml.Vector2f;
import org.joml.Vector3f;
import java.awt.image.BufferedImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.*;
import java.io.*;

/**
 *
 * @author Jesse
 */
public class World {

    /*
     * Properties
     */
    public static long WORLD_SEED;
    public FirstPersonCamera camera;
    public static int sizeX = 4096;
    public static int sizeY = 4096;
    public static ByteBuffer worldTexture;
    public static List<ByteBuffer> subTextures;
    /*
     * Counters and flags
     */
    public static boolean SWEEPER_IS_SLEEPING = true;
    public static boolean WAKE_SWEEPER = true;
    public static int MAX_CHUNKS_TO_SWEEP = 8;  //Max chunks to sweep per pass
    public static int MAX_CHUNKS_TO_BUILD = 8;  //Max chunk meshes to build per frame
    public static int MAX_CHUNKS_TO_GEN = 8;  //Max chunks to try to generate per frame
    public static int MAX_CHUNKS_TO_VBO = 8;  //Max chunks to try to push to VBO per frame
    private static int GEN_CHUNKS = 0;
    private static int BUILT_CHUNKS = 0;
    public static int VBO_CHUNKS = 0;
    /**
     * Guards all voxel data. Mesh builders run concurrently under the read lock;
     * terrain generation and player edits take the write lock. A single world-wide
     * lock avoids the deadlock risk of per-chunk locks, since meshing a chunk has
     * to read its neighbours across borders.
     */
    public static final java.util.concurrent.locks.ReentrantReadWriteLock BLOCK_LOCK =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** Set by {@link Input} when the player clicks; consumed during update. */
    public static volatile boolean BREAK_BLOCK_REQUESTED = false;
    public static volatile boolean PLACE_BLOCK_REQUESTED = false;
    public static boolean REBUILD_CHUNKS = false;
    public static int CURRENT_BOUND_XL = 0;
    public static int CURRENT_BOUND_XU = 0;
    public static int CURRENT_BOUND_YL = 0;
    public static int CURRENT_BOUND_YU = 0;
    /*
     * Data Structures
     */
    public static ArrayList<WorldChunk> chunks = new ArrayList<WorldChunk>();
    public static ArrayList<WorldChunk> destroyChunks = new ArrayList<WorldChunk>();
    public static ArrayList<WorldChunk> generateChunks = new ArrayList<WorldChunk>();
    /*
     * State
     */
    public static ExecutorService threadPool = Executors.newFixedThreadPool(3);

    public World() {
        Vector3f position = new Vector3f(Game.PLAYER_START_POSITION);
        camera = new FirstPersonCamera(position.x, position.y, position.z);
        subTextures = new ArrayList<ByteBuffer>();
        WORLD_SEED = new Random(System.nanoTime()).nextLong();
    }

    public World(Vector3f position) {
        camera = new FirstPersonCamera(position.x, position.y, position.z);
        subTextures = new ArrayList<ByteBuffer>();
        WORLD_SEED = new Random(System.nanoTime()).nextLong();
    }

    /**
     * Spatial index for chunk lookup. getChunk is called for every ambient
     * occlusion sample and every collision voxel, so a linear scan of the chunk
     * list showed up directly in frame time.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, WorldChunk> chunkIndex =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long chunkKey(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xFFFFFFFFL);
    }

    public static void registerChunk(WorldChunk chunk) {
        chunkIndex.put(chunkKey(chunk.posX, chunk.posY), chunk);
    }

    public static void unregisterChunk(WorldChunk chunk) {
        chunkIndex.remove(chunkKey(chunk.posX, chunk.posY), chunk);
    }

    public static WorldChunk getChunk(int x, int y) {
        return chunkIndex.get(chunkKey(x, y));
    }

    public static int getChunkIndex(int x, int y) {
        for (int i = 0; i < World.chunks.size(); i++) {
            WorldChunk chunk = World.chunks.get(i);
            if (chunk != null && chunk.posX == x && chunk.posY == y) {
                return i;
            }
        }
        return -1;
    }

    public static int getChunkIndex(WorldChunk chunk) {
        return World.chunks.indexOf(chunk);
    }

    public void loadTextures() {
        // Terrain atlas is loaded by Renderer via the STB-backed Texture class.
    }

    public void loadModels() {
        // Legacy OBJ/display-list model path removed with the fixed-function pipeline.
    }

    public static float getHeightAt(int x, int y) {
        float xPos = x / (float) (128.0f);
        float yPos = y / (float) (128.0f);
        double v = PerlinNoiseGenerator.getNoise(xPos, yPos, 3, 3.25f, World.sizeY);
        v += 1.0f;
        v = (v * (WorldChunk.sizeY / 2));
        return (int) v;
    }

    public void update() {
        BUILT_CHUNKS = 0;
        GEN_CHUNKS = 0;
        VBO_CHUNKS = 0;
        serializeAndFreeInactiveChunks();
        pickSelectedBlock();
    }

    private void pickSelectedBlock() {
        if (Game.FIND_SELECTED_BLOCK) {
            Block selectedBlock = BlockFinder.pickSelectedBlock();
            if (selectedBlock != null) {
                BlockFinder.setSelectedBlock(selectedBlock.x, selectedBlock.y, selectedBlock.z);
                handleSelectedBlock(selectedBlock);
            }
        }
    }

    /**
     * Applies any block break/place requested by {@link Input} since the last frame.
     * Input is captured on the main loop via GLFW and surfaced here as flags rather
     * than the old LWJGL 2 Mouse event queue.
     */
    private void handleSelectedBlock(Block selectedBlock) {
        if (selectedBlock == null) {
            BREAK_BLOCK_REQUESTED = false;
            PLACE_BLOCK_REQUESTED = false;
            return;
        }
        if (PLACE_BLOCK_REQUESTED) {
            PLACE_BLOCK_REQUESTED = false;
            int[] cell = BlockFinder.pickPlacementCell();
            if (cell != null && !wouldTrapPlayer(cell[0], cell[1], cell[2])) {
                BlockFinder.setBlockType(cell[0], cell[1], cell[2], Game.SELECTED_BLOCK_TYPE);
                Game.consoleMsg("Placed " + Block.nameOf(Game.SELECTED_BLOCK_TYPE)
                        + " at " + cell[0] + "," + cell[1] + "," + cell[2]);
            }
        }
        if (BREAK_BLOCK_REQUESTED) {
            BREAK_BLOCK_REQUESTED = false;
            BlockFinder.setBlockType(selectedBlock.x, selectedBlock.y, selectedBlock.z, 0);
            Game.consoleMsg("Broke a block at " + selectedBlock.x + "," + selectedBlock.y + "," + selectedBlock.z);
        }
    }

    /**
     * Rejects placements that would put a block inside the player's own box.
     */
    private static boolean wouldTrapPlayer(int worldX, int worldY, int worldZ) {
        Vector3d p = Game.GAME_CAMERA.position;
        double minX = p.x - 0.3, maxX = p.x + 0.3;
        double minY = p.y - 1.62, maxY = p.y + 0.18;
        double minZ = p.z - 0.3, maxZ = p.z + 0.3;
        return maxX > worldX && minX < worldX + 1
                && maxY > worldY && minY < worldY + 1
                && maxZ > worldZ && minZ < worldZ + 1;
    }

    private void serializeAndFreeInactiveChunks() {
        //If the sweeper thread is sleeping, wake it up - This looks for inactive chunks outside the camera's space
        if (SWEEPER_IS_SLEEPING && World.WAKE_SWEEPER) {
            SWEEPER_IS_SLEEPING = false;
            World.WAKE_SWEEPER = false;

            int SWEPT_CHUNKS = 0;
            synchronized (World.destroyChunks) {
                for (int i = 0; i < Math.min(destroyChunks.size(), World.MAX_CHUNKS_TO_SWEEP); i++) {

                    WorldChunk deadChunk = destroyChunks.get(i);
                    if (deadChunk != null) {
                        deadChunk.serialize();
                        deadChunk.deleteVBO();
                        chunks.remove(deadChunk);
                        unregisterChunk(deadChunk);
                        SWEPT_CHUNKS++;
                    } else {
                        System.out.println("Null position came back from sweeper");
                    }
                    destroyChunks.remove(i);
                }
            }

            Game.STAT_SWEPT_CHUNKS += SWEPT_CHUNKS;
            int chunkRadius = Game.OPT_DRAW_DISTANCE; //The number of chunks around the player to render
            int currentChunkX = (int) Math.floor(camera.position.x) / WorldChunk.sizeX;
            int currentChunkY = (int) Math.floor(camera.position.z) / WorldChunk.sizeZ;

            Runnable chunkSweeper = new WorldInactiveChunkSweeperThread(chunks, currentChunkX, currentChunkY, chunkRadius);
            threadPool.execute(chunkSweeper);
        }

    }

    //Render any WorldChunks that happen to be within the chunkRadius of the current camera position
    public void render() {
        if (Game.DEBUG_DRAW_CAMERA_RAY && !pickerRay.isEmpty()) {
            drawPickerRay();
        }

        int chunkRadius = Game.OPT_DRAW_DISTANCE; //The number of chunks around the player to render
        int currentChunkX = (int) Math.floor(camera.position.x) / WorldChunk.sizeX;
        int currentChunkY = (int) Math.floor(camera.position.z) / WorldChunk.sizeZ;
        
        World.CURRENT_BOUND_XL = Math.max(currentChunkX - chunkRadius, 0);         //Lower X
        World.CURRENT_BOUND_XU = Math.min(currentChunkX + chunkRadius, sizeX);  //Upper X
        World.CURRENT_BOUND_YL = Math.max(currentChunkY - chunkRadius, 0); // Lower Y
        World.CURRENT_BOUND_YU = Math.min(currentChunkY + chunkRadius, sizeY);  //Upper Y
        
        int midX = Math.abs(CURRENT_BOUND_XU - chunkRadius);
        int midY = Math.abs(CURRENT_BOUND_YU - chunkRadius);

        Renderer.beginChunkPass();
        
        for (int radius = 0; radius <= chunkRadius; radius++) {
            int xRadiusLower = Math.max(midX - radius, CURRENT_BOUND_XL);
            int yRadiusLower = Math.max(midY - radius, CURRENT_BOUND_YL);
            int xRadiusUpper = Math.min(midX + radius, CURRENT_BOUND_XU - 1);
            int yRadiusUpper = Math.min(midY + radius, CURRENT_BOUND_YU - 1);
            
            if (radius == 0) {
                renderChunk(xRadiusLower, yRadiusLower, currentChunkX, currentChunkY, radius, chunkRadius);
                continue;
            }
            
            //do all x+
            for (int i = xRadiusLower; i < xRadiusUpper; i++) {
                renderChunk(i, yRadiusLower, currentChunkX, currentChunkY, radius, chunkRadius);
            }
            
            //do all y+
            for (int i = yRadiusLower; i < yRadiusUpper; i++) {
                renderChunk(xRadiusUpper, i, currentChunkX, currentChunkY, radius, chunkRadius);
            }
            
            //do all x-
            for (int i = xRadiusUpper; i > xRadiusLower; i--) {
                renderChunk(i, yRadiusUpper, currentChunkX, currentChunkY, radius, chunkRadius);
            }
            
            //do all y-
            for (int i = yRadiusUpper; i > yRadiusLower; i--) {
                renderChunk(xRadiusLower, i, currentChunkX, currentChunkY, radius, chunkRadius);
            }
        }
        Game.STAT_BUILT_CHUNKS += BUILT_CHUNKS;

        Renderer.endChunkPass();
    }

    /** Debug visualisation of the block-picking ray, drawn with the line shader. */
    private void drawPickerRay() {
        int count = pickerRay.size();
        float[] points = new float[count * 3];
        for (int i = 0; i < count; i++) {
            Vector3d r = pickerRay.get(i);
            points[i * 3] = (float) ((int) r.x - Game.GAME_CAMERA.position.x);
            points[i * 3 + 1] = (float) (int) r.y;
            points[i * 3 + 2] = (float) ((int) r.z - Game.GAME_CAMERA.position.z);
        }
        Renderer.drawDebugGeometry(GL_POINTS, points, count, PICKER_RAY_MODEL, 1f, 0f, 1f, 1f);
    }

    private static final org.joml.Matrix4f PICKER_RAY_MODEL = new org.joml.Matrix4f();


    private void renderChunk(int i, int j, int currentChunkX, int currentChunkY, int innerRadius, int outerRadius) {
        WorldChunk thisChunk = World.getChunk(i, j);
        if (thisChunk == null && !Game.MEMORY_BOUND && World.GEN_CHUNKS < World.MAX_CHUNKS_TO_GEN) {
            thisChunk = new WorldChunk(i, j);
            chunks.add(thisChunk);
            registerChunk(thisChunk);
            //continue;
        }
        if (thisChunk != null) {
            if (thisChunk.isBuilding || thisChunk.isGenerating || thisChunk.isRefreshing) {
                return;
            }

            if (!thisChunk.isGenerated && !Game.MEMORY_BOUND && GEN_CHUNKS < World.MAX_CHUNKS_TO_GEN) {
                Runnable chunkBuilder = new WorldChunkLoadThread(thisChunk);
                threadPool.execute(chunkBuilder);
                GEN_CHUNKS++;
                return;
            }
            if (thisChunk.isGenerated && !thisChunk.isBuilt && !Game.MEMORY_BOUND && BUILT_CHUNKS < World.MAX_CHUNKS_TO_BUILD) {

                //This chunk is not building and not built, so lets build it..

                //Don't build meshes for chunks that are on the edge of the built list 
                //We can't know if the neighboring blocks are exposed until the neighbor is generated
                if (innerRadius < outerRadius - 1) {
                    thisChunk.isRefreshing = true;
                    Runnable chunkBufferBuilder = new WorldChunkBufferBuilderThread(thisChunk);
                    threadPool.execute(chunkBufferBuilder);
                    BUILT_CHUNKS++;
                }
                return;
            }
            if (thisChunk.isZombie) {
                destroyChunks.remove(thisChunk);
                thisChunk.isZombie = false;
            }
            //If the chunk is done (ready to render) and is immediately within the proximity of the current chunk or is otherwise within the frustum, render
            if (thisChunk.isReady()) {
                // Frustum culling must only skip DRAWING. Gating mesh generation on
                // visibility leaves permanent holes, because a chunk that was once
                // off-screen never builds and so never becomes drawable.
                boolean cullable = Game.OPT_CULL_CHUNKS && innerRadius > 1;
                if (!cullable || thisChunk.isVisible()) {
                    if (!thisChunk.vboIsStale) {
                        thisChunk.render();
                    } else if (VBO_CHUNKS < World.MAX_CHUNKS_TO_VBO) {
                        thisChunk.render();
                        VBO_CHUNKS++;
                    }
                }
                if (i == currentChunkX && j == currentChunkY) {
                    if (Game.OPT_BLOCK_COLLISION) {
                        thisChunk.findCameraBounds(camera);
                    }
                }
                thisChunk.selectedBlock = null;
            }
        }
    }

    public static boolean allNeighborsAreGenerated(WorldChunk chunk) {
        WorldChunk[] neighbors = new WorldChunk[4];
        boolean[] generated = new boolean[]{false, false, false, false};

        if (chunk.posX > 0 && chunk.posX > World.CURRENT_BOUND_XL) {
            neighbors[1] = World.getChunk(chunk.posX - 1, chunk.posY);
            if (neighbors[1] != null) {
                generated[1] = neighbors[1].isGenerated;
            }
        } else {
            generated[1] = true;
        }
        if (chunk.posY > 0 && chunk.posY > World.CURRENT_BOUND_YL) {
            neighbors[3] = World.getChunk(chunk.posX, chunk.posY - 1);
            if (neighbors[3] != null) {
                generated[3] = neighbors[3].isGenerated;
            }
        } else {
            generated[3] = true;

        }
        if (chunk.posX < sizeX - 1 && chunk.posX < World.CURRENT_BOUND_XU) {
            neighbors[0] = World.getChunk(chunk.posX + 1, chunk.posY);
            if (neighbors[0] != null) {
                generated[0] = neighbors[0].isGenerated;
            }
        } else {
            generated[0] = true;
        }

        if (chunk.posY < sizeY - 1 && chunk.posY < World.CURRENT_BOUND_YU) {
            neighbors[2] = World.getChunk(chunk.posX, chunk.posY + 1);
            if (neighbors[2] != null) {
                generated[2] = neighbors[2].isGenerated;
            }
        } else {
            generated[2] = true;
        }

        return (generated[0] && generated[1] && generated[2] && generated[3]);
    }

    /**
     * Solidity test for movement. Water is passable so lakes can be waded into.
     */
    public static boolean isSolidForCollision(int worldX, int worldY, int worldZ) {
        if (worldY < 0) {
            return true;
        }
        if (worldY >= WorldChunk.sizeY) {
            return false;
        }
        int chunkX = Math.floorDiv(worldX, WorldChunk.sizeX);
        int chunkZ = Math.floorDiv(worldZ, WorldChunk.sizeZ);
        WorldChunk chunk = World.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.isGenerated || chunk.blocks == null) {
            return false;   // never trap the player inside unloaded terrain
        }
        int type = chunk.blocks[Math.floorMod(worldX, WorldChunk.sizeX)][worldY][Math.floorMod(worldZ, WorldChunk.sizeZ)];
        return type != Block.AIR && type != Block.WATER;
    }

    /**
     * Solidity by absolute world coordinates, used for ambient occlusion across
     * chunk borders. Unloaded chunks count as solid so seams do not flash bright.
     */
    public static boolean isSolidGlobal(int worldX, int y, int worldZ) {
        if (y < 0) {
            return true;
        }
        if (y >= WorldChunk.sizeY) {
            return false;
        }
        int chunkX = Math.floorDiv(worldX, WorldChunk.sizeX);
        int chunkZ = Math.floorDiv(worldZ, WorldChunk.sizeZ);
        WorldChunk chunk = World.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.isGenerated || chunk.blocks == null) {
            return true;
        }
        int lx = Math.floorMod(worldX, WorldChunk.sizeX);
        int lz = Math.floorMod(worldZ, WorldChunk.sizeZ);
        return !Block.isTransparent(chunk.blocks[lx][y][lz]);
    }

    //Looks at the neigher of a block on the edge of a chunk
    //Direction - 1:up, 2:down, 3:left, 4:right
    public static int chunkNeighbor(int direction, int x, int y, int z, int chunkX, int chunkY) {
        WorldChunk chunk = null;
        int block = 1;
        switch (direction) {
            case 1: //Up  
                //TODO: Block until the chunk is done
                if (chunkX < sizeX - 1 && chunkY >= 0 && chunkY < sizeY) {
                    chunk = World.getChunk(chunkX + 1, chunkY);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.blocks[0][y][z];
                    }
                }
                break;
            case 2: //Down
                //TODO: Block until the chunk is done
                if (chunkX > 0 && chunkY >= 0 && chunkY < sizeY) {
                    chunk = World.getChunk(chunkX - 1, chunkY);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.blocks[WorldChunk.sizeX - 1][y][z];
                    }
                }
                break;
            case 3: //Left
                //TODO: Block until the chunk is done
                if (chunkY > 0 && chunkX >= 0 && chunkX < sizeX) {
                    chunk = World.getChunk(chunkX, chunkY - 1);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.blocks[x][y][WorldChunk.sizeZ - 1];
                    }
                }
                break;
            case 4: //Right
                //TODO: Block until the chunk is done
                if (chunkY < sizeY - 1 && chunkX >= 0 && chunkX < sizeX) {
                    chunk = World.getChunk(chunkX, chunkY + 1);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.blocks[x][y][0];
                    }
                }
                break;
        }
        return block;  //A null block - empty space
    }

    public static WorldChunk chunkNeighbor(int direction, WorldChunk chunk) {
        switch (direction) {
            case 1: //Up  
                return World.getChunk(chunk.posX + 1, chunk.posY);
            case 2: //Down
                return World.getChunk(chunk.posX - 1, chunk.posY);
            case 3: //Left
                return World.getChunk(chunk.posX, chunk.posY - 1);
            case 4: //Right
                return World.getChunk(chunk.posX, chunk.posY + 1);
        }
        return null;
    }

    public static long getSeed() {
        return WORLD_SEED;
    }
}
