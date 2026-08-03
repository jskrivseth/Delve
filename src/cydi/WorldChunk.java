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
    /**
     * Sky light per voxel, flattened as (x * sizeY + y) * sizeZ + z.
     *
     * A byte array rather than int[][][] because this is rebuilt on every mesh
     * pass and touched once per voxel.
     */
    private volatile byte[] skyLight;
    /** Scratch BFS queue, reused across rebuilds to avoid per-pass allocation. */
    private transient int[] lightQueue;
    private transient int queueHead;
    private transient int queueTail;
    /**
     * Light values along the four chunk borders as of the last computation.
     *
     * Lighting is seeded from neighbours, so when this chunk's border values
     * change the neighbours' copies are stale and must be recomputed. Comparing
     * against this snapshot drives that, and stops once values settle.
     */
    private transient byte[] borderSnapshot;
    /** Packed biome tint at each block corner, rebuilt with the mesh. */
    private transient float[] tintGrid;
    private transient float[] groundTintGrid;
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
    public transient float renderAlpha = 1.0f;
    /** How long a chunk takes to fade in when built and fade out when destroyed. */
    /** How long a chunk takes to fade in when built and fade out when destroyed. */
    private static long fadeDurationNanos() {
        return (long) (Game.OPT_CHUNK_FADE_DURATION_MS * 1_000_000.0);
    }
    /**
     * Wall-clock time this chunk's VBO was first uploaded, or -1 before that.
     * Stamped once per chunk instance in {@link #buildVBO()} and never reset by
     * ordinary mesh rebuilds from block edits (those reuse the same VBO handle
     * and never pass back through the "first build" branch), so editing blocks
     * near an already-visible chunk never restarts its fade-in.
     */
    private transient volatile long meshReadyAtNanos = -1L;
    /**
     * Wall-clock time this chunk was first detected as due for destruction, or
     * -1 while it isn't. Deliberately time-based rather than frame-counted: the
     * sweeper's keep radius defaults to the same size as the render/draw-distance
     * radius, so a chunk usually stops being visited by the render loop in the
     * same instant it becomes sweep-eligible. A frame-counted timer driven only
     * from the render loop would then never advance again once the chunk stops
     * being drawn, so it would never finish fading and never actually get freed
     * -- a permanent chunk/GPU-handle leak. A wall-clock timestamp instead keeps
     * advancing regardless of whether anything is currently drawing the chunk.
     */
    private transient volatile long destroyRequestedAtNanos = -1L;
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

    public static WorldChunk getCurrentChunk() {
        int x = (int) Math.floor(Game.GAME_CAMERA.position.x / WorldChunk.sizeX);
        int y = (int) Math.floor(Game.GAME_CAMERA.position.z / WorldChunk.sizeZ);
        return World.getChunk(x, y);
    }


    public void generate() {
        if (this.isGenerating) {
            System.out.println("ERROR: attempt to generate a block already being generated");
            return;
        }
        this.isGenerating = true;
        try {
            generateBlocks();
        } finally {
            // Cleared in a finally block because a chunk left flagged as
            // generating is skipped by the renderer forever, leaving a
            // permanent chunk-shaped hole in the world.
            this.isGenerating = false;
        }
    }

    private void generateBlocks() {
        // A previously edited chunk is restored from disk instead of being
        // regenerated, otherwise the player's changes vanish when it reloads.
        if (this.load()) {
            recomputeMaxHeight();
            this.isModified = true;
            this.isGenerated = true;
            this.isBuilt = false;
            return;
        }

        int[][][] data = new int[sizeX][sizeY][sizeZ];
        int[][] heightMap = new int[sizeX][sizeZ];
        int[][] surfaceMap = new int[sizeX][sizeZ];
        int[][] biomeTypeMap = new int[sizeX][sizeZ];
        float[][] biomeBorderMap = new float[sizeX][sizeZ];
        float[][] tundraWeightMap = new float[sizeX][sizeZ];
        float[][] desertWeightMap = new float[sizeX][sizeZ];
        float[][] forestWeightMap = new float[sizeX][sizeZ];
        float[][] grassyWeightMap = new float[sizeX][sizeZ];
        float[][] ruggednessMap = new float[sizeX][sizeZ];
        float[][] wetlandMap = new float[sizeX][sizeZ];
        int worldPreset = WorldPreset.clamp(World.WORLD_PRESET);
        int highest = 1;

        // Climate + relief driven terrain. Temperature/moisture choose a simple
        // biome, while ruggedness controls whether an area is flat or mountainy.
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int worldX = worldPosX + x;
                int worldZ = worldPosY + z;
                double wx = warpedX(worldX, worldZ);
                double wz = warpedZ(worldX, worldZ);

                // Medium-frequency climate so biome patches are smaller.
                float temperature = sample01(wx, wz, 230.0, 0, 0);
                float moisture = sample01(wx, wz, 240.0, 137, -89);
                // Add detail-scale climate variation to break up giant blobs.
                temperature = clamp01(temperature + (sample01(wx, wz, 120.0, 311, -173) - 0.5f) * 0.26f);
                moisture = clamp01(moisture + (sample01(wx, wz, 130.0, -421, 257) - 0.5f) * 0.30f);
                float ruggedness = sample01(wx, wz, 560.0, -211, 173);
                float continent = sample01(wx, wz, 960.0, 41, -271);
                float macro = sample01(wx, wz, 1800.0, 73, -511);
                float mountainRegion = sample01(wx, wz, 1350.0, -733, 419);
                BiomeBlend biome = blendBiomes(temperature, moisture, wx, wz);
                BiomeBlend ditheredBiome = biome.dithered(sample01(wx, wz, 42.0, 177, -233));

                // Macro zones: lowlands and highlands modulate base and relief
                // smoothly so large regions share a broad topography.
                float highlands = smoothstep(0.54f, 0.80f, macro) * smoothstep(0.50f, 0.82f, mountainRegion);
                float lowlands = (1.0f - smoothstep(0.18f, 0.36f, macro))
                        * (1.0f - smoothstep(0.58f, 0.84f, mountainRegion));

                float detail = fbm01(wx, wz);
                float micro = fbm01(wx * 2 + 311, wz * 2 - 227);
                float clumpPrimary = sample01(wx, wz, 780.0, -503, 907);
                float clumpSecondary = sample01(wx, wz, 360.0, 211, -311);
                float dramaticBand = smoothstep(0.52f, 0.80f, clumpPrimary + (clumpSecondary - 0.5f) * 0.32f);
                float ridgeNoise = (float) PerlinNoiseGenerator.getNoise(wx / 190.0, wz / 190.0);
                float ridge = 1.0f - Math.abs(ridgeNoise);

                float desertW = ditheredBiome.weight(BiomeDefinition.DESERT);
                float tundraW = ditheredBiome.weight(BiomeDefinition.TUNDRA);
                float forestW = ditheredBiome.weight(BiomeDefinition.FOREST);
                float grassyW = ditheredBiome.weight(BiomeDefinition.GRASSY);
                float biomeDrama = desertW * BiomeDefinition.ALL[BiomeDefinition.DESERT].dramaBias
                        + tundraW * BiomeDefinition.ALL[BiomeDefinition.TUNDRA].dramaBias
                        + forestW * BiomeDefinition.ALL[BiomeDefinition.FOREST].dramaBias
                        + grassyW * BiomeDefinition.ALL[BiomeDefinition.GRASSY].dramaBias;
                float clumpFactor = lerp(0.78f, 2.05f, clamp01(dramaticBand * biomeDrama));
                float mountainMask = smoothstep(0.50f, 0.86f,
                        mountainRegion * 0.62f + highlands * 0.34f + dramaticBand * 0.18f);
                // Colder climates support stronger alpine relief.
                mountainMask *= lerp(0.82f, 1.15f, tundraW);

                float relief = lerp(0.15f, 0.42f, ruggedness);
                // Biome parameters are blended rather than hard-switched, so
                // borders transition by continuously changing terrain shape.
                float biomeRelief = desertW * BiomeDefinition.ALL[BiomeDefinition.DESERT].reliefScale
                        + tundraW * BiomeDefinition.ALL[BiomeDefinition.TUNDRA].reliefScale
                        + forestW * BiomeDefinition.ALL[BiomeDefinition.FOREST].reliefScale
                        + grassyW * BiomeDefinition.ALL[BiomeDefinition.GRASSY].reliefScale;
                relief *= biomeRelief;
                relief *= lerp(0.74f, 1.42f, highlands);
                relief *= lerp(0.70f, 1.00f, 1.0f - lowlands);
                relief *= clumpFactor;
                relief *= lerp(1.0f, 2.35f, mountainMask);

                // Fine-grained biome character, mixed by climate weight so an
                // alpine ridge eases into forest instead of ending at a wall.
                float earthRelief = 1.0f;
                float earthBase = 0.0f;
                if (worldPreset == WorldPreset.EARTH) {
                    float[] climate = earthClimateWeights(temperature, moisture, ruggedness,
                            highlands, lowlands, mountainMask, 3.0f);
                    earthRelief = 0.0f;
                    for (int i = 0; i < climate.length; i++) {
                        earthRelief += climate[i] * EarthBiome.RELIEF_SCALE[i];
                        earthBase += climate[i] * EarthBiome.BASE_BIAS[i];
                    }
                }
                relief *= earthRelief;

                float biomeBase = desertW * BiomeDefinition.ALL[BiomeDefinition.DESERT].baseBias
                        + tundraW * BiomeDefinition.ALL[BiomeDefinition.TUNDRA].baseBias
                        + forestW * BiomeDefinition.ALL[BiomeDefinition.FOREST].baseBias
                        + grassyW * BiomeDefinition.ALL[BiomeDefinition.GRASSY].baseBias;
                float base = 0.22f + continent * 0.30f + biomeBase + earthBase
                        + highlands * 0.08f - lowlands * 0.06f; // continental scale + macro zones
                base += mountainMask * 0.09f;
                float clumpN = clamp01((clumpFactor - 0.78f) / (2.05f - 0.78f));
                float broad = (detail - 0.5f) * 2.0f;
                float fine = (micro - 0.5f) * 2.0f;
                float ridged = (ridge - 0.5f) * 2.0f;
                float terrainSignal = broad * (0.74f + 0.30f * clumpN)
                        + fine * (0.14f + 0.58f * clumpN)
                        + ridged * (0.10f + 0.90f * mountainMask);
                float normalizedHeight = base + terrainSignal * relief * 0.88f;
                // Keep tundra bands notably more alpine than surrounding climates.
                normalizedHeight += tundraW * (0.040f + highlands * 0.028f);
                normalizedHeight = remapHeightForPreset(worldPreset, normalizedHeight, ruggedness, highlands, lowlands, mountainMask, detail, micro, ridge);
                int height = (int) (normalizedHeight * (sizeY - 6));
                if (height > sizeY - 4) {
                    height = sizeY - 4;
                }
                if (height < 1) {
                    height = 1;
                }

                float wetland = clamp01((moisture - 0.58f) * 2.4f)
                        * clamp01((SEA_LEVEL + 10 - height) / 14.0f)
                        * clamp01(1.0f - ruggedness * 1.1f)
                        * lowlands;

                int biomeType = -1;
                float biomeBorder = 0f;
                if (worldPreset == WorldPreset.EARTH) {
                    float[] scores = earthBiomeScores(temperature, moisture, ruggedness, height, wetland, ditheredBiome);
                    int primary = bestIndex(scores);
                    int secondary = runnerUpIndex(scores, primary);
                    biomeBorder = borderStrength(scores, primary, secondary);
                    // One dithered decision per column drives the surface block,
                    // the filler beneath it, the trees and the ground cover, so a
                    // spilled-over patch is consistent all the way down.
                    biomeType = ditherBiome(primary, secondary, biomeBorder, worldX, worldZ);
                }
                int surface = worldPreset == WorldPreset.EARTH
                        ? surfaceTypeFor(height, ditheredBiome, ruggedness, temperature, highlands, wetland, worldX, worldZ, biomeType)
                        : surfaceTypeForPlanet(worldPreset, height, ruggedness, temperature, moisture, worldX, worldZ, highlands, lowlands);
                heightMap[x][z] = height;
                surfaceMap[x][z] = surface;
                biomeTypeMap[x][z] = biomeType;
                biomeBorderMap[x][z] = biomeBorder;
                tundraWeightMap[x][z] = ditheredBiome.weight(BiomeDefinition.TUNDRA);
                desertWeightMap[x][z] = ditheredBiome.weight(BiomeDefinition.DESERT);
                forestWeightMap[x][z] = ditheredBiome.weight(BiomeDefinition.FOREST);
                grassyWeightMap[x][z] = ditheredBiome.weight(BiomeDefinition.GRASSY);
                ruggednessMap[x][z] = ruggedness;
                wetlandMap[x][z] = wetland;
                for (int y = 0; y <= height; y++) {
                    int type;
                    if (y == 0) {
                        type = Block.BEDROCK;
                    } else if (y == height) {
                        type = surface;
                    } else if (y >= height - 3) {
                        type = worldPreset == WorldPreset.EARTH
                                ? fillerTypeFor(surface, ditheredBiome, y, height, worldX, worldZ, wetland)
                                : fillerTypeForPlanet(worldPreset, surface, y, height);
                    } else {
                        type = worldPreset == WorldPreset.EARTH
                                ? geologyTypeFor(y, worldX, worldZ)
                                : geologyTypeForPlanet(worldPreset, y, worldX, worldZ);
                    }
                    data[x][y][z] = type;
                }

                if (worldPreset == WorldPreset.EARTH) {
                    // Flood everything below sea level so lakes get a flat surface.
                    for (int y = height + 1; y <= SEA_LEVEL; y++) {
                        data[x][y][z] = Block.WATER;
                    }
                    // Wetland puddles above sea level.
                    if (wetland > 0.56f && height >= SEA_LEVEL + 1 && height <= SEA_LEVEL + 6
                            && (hash(worldX, worldZ, height, 404) % 100) < 34) {
                        data[x][height][z] = Block.CLAY;
                        if (height + 1 < sizeY - 1) {
                            data[x][height + 1][z] = Block.WATER;
                        }
                    }
                } else if (worldPreset == WorldPreset.TRITON) {
                    for (int y = height + 1; y <= SEA_LEVEL - 4; y++) {
                        data[x][y][z] = Block.FROST_ICE;
                    }
                }

                int top = Math.max(height, height < SEA_LEVEL ? SEA_LEVEL : height);
                if (top + 1 > highest) {
                    highest = top + 1;
                }
            }
        }

        if (worldPreset == WorldPreset.EARTH) {
            highest = plantTrees(data, heightMap, surfaceMap, biomeTypeMap, biomeBorderMap, tundraWeightMap, desertWeightMap, forestWeightMap, grassyWeightMap, ruggednessMap, wetlandMap, highest);
            highest = plantVegetation(data, heightMap, surfaceMap, biomeTypeMap, biomeBorderMap, tundraWeightMap, desertWeightMap, forestWeightMap, grassyWeightMap, wetlandMap, highest);
        } else {
            highest = placePlanetBoulders(data, heightMap, surfaceMap, ruggednessMap, worldPreset, highest);
        }

        this.blocks = data;
        this.maxHeight = Math.min(highest + 1, sizeY);

        this.isGenerated = true;
        this.isBuilt = false;
    }

    /** Sea level; columns below this are flooded with water. */
    public static final int MAX_LIGHT = 15;
    public static final int SEA_LEVEL = 32;

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / Math.max(edge1 - edge0, 0.0001f));
        return t * t * (3f - 2f * t);
    }

    private static float sample01(int worldX, int worldZ, double scale, int ox, int oz) {
        return (float) ((PerlinNoiseGenerator.getNoise((worldX + ox) / scale, (worldZ + oz) / scale) + 1.0) * 0.5);
    }

    private static float sample01(double worldX, double worldZ, double scale, int ox, int oz) {
        return (float) ((PerlinNoiseGenerator.getNoise((worldX + ox) / scale, (worldZ + oz) / scale) + 1.0) * 0.5);
    }

    private static float fbm01(int worldX, int worldZ) {
        double n = 0.0;
        double amp = 1.0;
        double freq = 1.0 / 140.0;
        double sum = 0.0;
        for (int i = 0; i < 5; i++) {
            n += PerlinNoiseGenerator.getNoise(worldX * freq, worldZ * freq) * amp;
            sum += amp;
            amp *= 0.52;
            freq *= 2.08;
        }
        float signed = (float) (n / Math.max(sum, 0.0001));
        return (signed + 1.0f) * 0.5f;
    }

    private static float fbm01(double worldX, double worldZ) {
        double n = 0.0;
        double amp = 1.0;
        double freq = 1.0 / 140.0;
        double sum = 0.0;
        for (int i = 0; i < 5; i++) {
            n += PerlinNoiseGenerator.getNoise(worldX * freq, worldZ * freq) * amp;
            sum += amp;
            amp *= 0.52;
            freq *= 2.08;
        }
        float signed = (float) (n / Math.max(sum, 0.0001));
        return (signed + 1.0f) * 0.5f;
    }

    private static double warpedX(int worldX, int worldZ) {
        double broad = PerlinNoiseGenerator.getNoise((worldX - 413) / 430.0, (worldZ + 271) / 430.0);
        double detail = PerlinNoiseGenerator.getNoise((worldX + 193) / 150.0, (worldZ - 877) / 150.0);
        return worldX + broad * 92.0 + detail * 28.0;
    }

    private static double warpedZ(int worldX, int worldZ) {
        double broad = PerlinNoiseGenerator.getNoise((worldX + 521) / 430.0, (worldZ - 149) / 430.0);
        double detail = PerlinNoiseGenerator.getNoise((worldX - 739) / 150.0, (worldZ + 347) / 150.0);
        return worldZ + broad * 92.0 + detail * 28.0;
    }

    private static BiomeBlend blendBiomes(float temperature, float moisture, double worldX, double worldZ) {
        BiomeBlend blend = new BiomeBlend();
        // Local perturbation so transition bands meander instead of forming wide
        // smooth corridors across the whole world.
        float tw = (sample01(worldX, worldZ, 150.0, -91, 63) - 0.5f) * 0.18f;
        float mw = (sample01(worldX, worldZ, 150.0, 127, -147) - 0.5f) * 0.18f;
        float t = clamp01(temperature + tw);
        float m = clamp01(moisture + mw);
        float sum = 0f;
        for (BiomeDefinition def : BiomeDefinition.ALL) {
            float dx = t - def.centerTemp;
            float dz = m - def.centerMoisture;
            float dist2 = dx * dx + dz * dz;
            // Inverse-distance weighting gives a smooth blend where every
            // parameter can be mixed continuously instead of hard switching.
            float w = 1.0f / (dist2 + 0.010f);
            // Reduce grassy/forest dominance so uncommon climates appear more.
            if (def.id == BiomeDefinition.TUNDRA || def.id == BiomeDefinition.DESERT) {
                w *= 1.18f;
            } else {
                w *= 0.88f;
            }
            // Sharpen blending a bit so patches are distinct while still smooth.
            w = (float) Math.pow(w, 1.18);
            blend.setWeight(def.id, w);
            sum += w;
        }
        if (sum > 0f) {
            for (BiomeDefinition def : BiomeDefinition.ALL) {
                blend.setWeight(def.id, blend.weight(def.id) / sum);
            }
        }
        return blend;
    }

    private static int classifyEarthBiome(float temperature, float moisture, float ruggedness,
                                          int height, float wetland, BiomeBlend biome) {
        return bestIndex(earthBiomeScores(temperature, moisture, ruggedness, height, wetland, biome));
    }

    private static float[] earthBiomeScores(float temperature, float moisture, float ruggedness,
                                            int height, float wetland, BiomeBlend biome) {
        float hot = smoothstep(0.60f, 0.84f, temperature);
        float cold = 1.0f - smoothstep(0.24f, 0.44f, temperature);
        float wet = smoothstep(0.52f, 0.82f, moisture);
        float dry = 1.0f - smoothstep(0.30f, 0.56f, moisture);
        float alpineFactor = smoothstep(80.0f, 102.0f, height) * smoothstep(0.46f, 0.78f, ruggedness);
        float lowlandFactor = 1.0f - smoothstep(58.0f, 84.0f, height);

        float[] scores = new float[EarthBiome.COUNT];
        scores[EarthBiome.ALPINE] = alpineFactor;
        scores[EarthBiome.WETLAND] = wetland * lowlandFactor * 1.2f;
        scores[EarthBiome.HOT_DESERT] = hot * dry;
        scores[EarthBiome.SAVANNA] = hot * (1.0f - dry) * (1.0f - wet) * 0.95f;
        scores[EarthBiome.TROPICAL_RAINFOREST] = hot * wet * 1.1f;
        scores[EarthBiome.TUNDRA] = cold * smoothstep(66.0f, 96.0f, height) * 1.05f;
        scores[EarthBiome.BOREAL_FOREST] = cold * wet * (1.0f - alpineFactor) * 0.98f;
        scores[EarthBiome.TEMPERATE_GRASSLAND] = (1.0f - hot) * (1.0f - cold) * dry;
        scores[EarthBiome.SHRUBLAND] = (1.0f - cold) * (1.0f - wet) * (1.0f - dry) * 0.92f;
        scores[EarthBiome.TEMPERATE_FOREST] = (1.0f - hot * 0.5f) * wet * 0.96f;

        // Keep legacy Earth blend influences in play so migration stays coherent.
        scores[EarthBiome.HOT_DESERT] += biome.weight(BiomeDefinition.DESERT) * 0.38f;
        scores[EarthBiome.TUNDRA] += biome.weight(BiomeDefinition.TUNDRA) * 0.34f;
        scores[EarthBiome.TEMPERATE_FOREST] += biome.weight(BiomeDefinition.FOREST) * 0.28f;
        scores[EarthBiome.TEMPERATE_GRASSLAND] += biome.weight(BiomeDefinition.GRASSY) * 0.24f;
        return scores;
    }

    /**
     * Biome weights driven purely by climate and noise fields.
     *
     * Terrain height is blended from these rather than from the classified biome,
     * because the classifier reads the finished height: feeding its result back
     * into elevation would make the biome and the terrain define each other.
     */
    private static float[] earthClimateWeights(float temperature, float moisture, float ruggedness,
                                               float highlands, float lowlands, float mountainMask,
                                               float sharpen) {
        float hot = smoothstep(0.60f, 0.84f, temperature);
        float cold = 1.0f - smoothstep(0.24f, 0.44f, temperature);
        float wet = smoothstep(0.52f, 0.82f, moisture);
        float dry = 1.0f - smoothstep(0.30f, 0.56f, moisture);
        float alpineProxy = mountainMask * smoothstep(0.46f, 0.78f, ruggedness);
        float wetProxy = clamp01((moisture - 0.58f) * 2.4f) * lowlands * clamp01(1.0f - ruggedness * 1.1f);
        
        float[] w = new float[EarthBiome.COUNT];
        w[EarthBiome.ALPINE] = alpineProxy;
        w[EarthBiome.WETLAND] = wetProxy * 1.2f;
        w[EarthBiome.HOT_DESERT] = hot * dry;
        w[EarthBiome.SAVANNA] = hot * (1.0f - dry) * (1.0f - wet) * 0.95f;
        w[EarthBiome.TROPICAL_RAINFOREST] = hot * wet * 1.1f;
        w[EarthBiome.TUNDRA] = cold * (0.35f + highlands * 0.65f) * 1.05f;
        w[EarthBiome.BOREAL_FOREST] = cold * wet * (1.0f - alpineProxy) * 0.98f;
        w[EarthBiome.TEMPERATE_GRASSLAND] = (1.0f - hot) * (1.0f - cold) * dry;
        w[EarthBiome.SHRUBLAND] = (1.0f - cold) * (1.0f - wet) * (1.0f - dry) * 0.92f;
        w[EarthBiome.TEMPERATE_FOREST] = (1.0f - hot * 0.5f) * wet * 0.96f;
        
        // Sharpen before normalising, otherwise averaging ten biomes everywhere
        // flattens the whole world to the same middling terrain.
        float sum = 0f;
        for (int i = 0; i < w.length; i++) {
            float v = (float) Math.pow(Math.max(w[i], 0f), sharpen);
            w[i] = v;
            sum += v;
        }
        if (sum <= 1e-6f) {
            java.util.Arrays.fill(w, 1.0f / w.length);
            return w;
        }
        for (int i = 0; i < w.length; i++) {
            w[i] /= sum;
        }
        return w;
    }

    private static int bestIndex(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int runnerUpIndex(float[] scores, int best) {
        int second = -1;
        for (int i = 0; i < scores.length; i++) {
            if (i == best) {
                continue;
            }
            if (second < 0 || scores[i] > scores[second]) {
                second = i;
            }
        }
        return second < 0 ? best : second;
    }

    /**
     * How contested a column is, as the runner-up's share of the winning score.
     *
     * Zero well inside a biome and one where two biomes score equally, so the
     * width of a transition follows how gradually the climate actually changes.
     */
    private static float borderStrength(float[] scores, int best, int second) {
        float top = Math.max(scores[best], 0f);
        if (top <= 1e-5f) {
            return 0f;
        }
        float ratio = clamp01(Math.max(scores[second], 0f) / top);
        return smoothstep(0.52f, 0.97f, ratio);
    }

    /**
     * Picks between a column's two strongest biomes.
     *
     * A coherent patch field modulates the threshold so the seam interlocks in
     * fingers instead of dissolving into uniform speckle, while a per-column roll
     * frays the very edge. The chance of taking the neighbour peaks at an even
     * split and falls off to nothing as one biome takes over.
     */
    private static int ditherBiome(int primary, int secondary, float border, int worldX, int worldZ) {
        if (primary == secondary || border <= 0.02f) {
            return primary;
        }
        float patch = sample01(worldX, worldZ, 13.0, 617, -431);
        float roll = (hash(worldX, worldZ, 0, 733) & 0xFFFF) / 65535.0f;
        float mix = border * 0.5f;
        return roll < mix * (0.35f + patch * 1.30f) ? secondary : primary;
    }

    private static float remapHeightForPreset(int preset, float normalizedHeight, float ruggedness,
                                              float highlands, float lowlands, float mountainMask,
                                              float detail, float micro, float ridge) {
        if (preset == WorldPreset.MARS) {
            float craterBand = smoothstep(0.46f, 0.78f, detail * 0.65f + micro * 0.35f);
            return normalizedHeight * 0.88f
                    + ruggedness * 0.05f
                    + mountainMask * 0.10f
                    + (ridge - 0.5f) * 0.14f
                    - craterBand * 0.06f;
        }
        if (preset == WorldPreset.VENUS) {
            float volcanicSwells = smoothstep(0.42f, 0.74f, micro * 0.55f + ridge * 0.45f);
            return normalizedHeight * 0.92f
                    + highlands * 0.07f
                    + volcanicSwells * 0.09f
                    - lowlands * 0.03f;
        }
        if (preset == WorldPreset.TRITON) {
            float icyPlains = smoothstep(0.35f, 0.70f, detail * 0.58f + ridge * 0.42f);
            return normalizedHeight * 0.82f
                    - lowlands * 0.06f
                    + icyPlains * 0.07f
                    + (ruggedness - 0.5f) * 0.05f;
        }
        return normalizedHeight;
    }

    private static int surfaceTypeForPlanet(int preset, int height, float ruggedness,
                                            float temperature, float moisture,
                                            int worldX, int worldZ, float highlands, float lowlands) {
        if (preset == WorldPreset.MARS) {
            float dunes = sample01(worldX, worldZ, 120.0, 409, -211);
            if (height >= 88 && ruggedness > 0.66f) {
                return Block.BASALT;
            }
            if (dunes > 0.62f && ruggedness < 0.56f) {
                return Block.RED_SAND;
            }
            return Block.RED_SANDSTONE;
        }
        if (preset == WorldPreset.VENUS) {
            float sulfurPatch = sample01(worldX, worldZ, 155.0, -317, 503);
            if (height >= 94 && ruggedness > 0.70f) {
                return Block.BASALT;
            }
            if (sulfurPatch > 0.60f || (highlands > 0.56f && temperature > 0.62f)) {
                return Block.SULFUR_STONE;
            }
            return ruggedness > 0.52f ? Block.BASALT : Block.VOLCANIC_ASH;
        }
        // Triton: frozen nitrogen plains with darker streaks.
        float streak = sample01(worldX, worldZ, 90.0, 97, -613);
        if (height >= 84 && ruggedness > 0.62f) {
            return Block.FROST_ICE;
        }
        if (streak > 0.72f && lowlands > 0.35f) {
            return Block.THOLIN;
        }
        return Block.SLUSH;
    }

    private static int fillerTypeForPlanet(int preset, int surface, int y, int height) {
        int depth = height - y;
        if (preset == WorldPreset.MARS) {
            if (surface == Block.RED_SAND) {
                return depth >= 3 ? Block.RED_SANDSTONE : Block.RED_SAND;
            }
            return depth >= 3 ? Block.BASALT : Block.RED_SANDSTONE;
        }
        if (preset == WorldPreset.VENUS) {
            if (surface == Block.SULFUR_STONE) {
                return depth >= 3 ? Block.BASALT : Block.SULFUR_STONE;
            }
            if (surface == Block.VOLCANIC_ASH) {
                return depth >= 3 ? Block.BASALT : Block.VOLCANIC_ASH;
            }
            return Block.BASALT;
        }
        if (surface == Block.FROST_ICE) {
            return depth <= 2 ? Block.FROST_ICE : Block.SLUSH;
        }
        if (surface == Block.THOLIN) {
            return depth <= 2 ? Block.THOLIN : Block.SLUSH;
        }
        return depth <= 1 ? Block.SLUSH : Block.FROST_ICE;
    }

    private static int geologyTypeForPlanet(int preset, int y, int worldX, int worldZ) {
        float a = (float) PerlinNoiseGenerator.getNoise(worldX / 92.0, (worldZ + y * 2) / 92.0);
        if (preset == WorldPreset.MARS) {
            if (a > 0.22f) {
                return Block.BASALT;
            }
            if (a > -0.12f) {
                return Block.RED_SANDSTONE;
            }
            return Block.DEEPSLATE;
        }
        if (preset == WorldPreset.VENUS) {
            if (a > 0.28f) {
                return Block.BASALT;
            }
            if (a > -0.05f) {
                return Block.VOLCANIC_ASH;
            }
            return Block.GRANITE;
        }
        if (a > 0.24f) {
            return Block.FROST_ICE;
        }
        if (a > -0.04f) {
            return Block.SLUSH;
        }
        return Block.THOLIN;
    }

    private int placePlanetBoulders(int[][][] data, int[][] heightMap, int[][] surfaceMap,
                                    float[][] ruggednessMap, int preset, int highest) {
        for (int x = 2; x < sizeX - 2; x++) {
            for (int z = 2; z < sizeZ - 2; z++) {
                int y = heightMap[x][z];
                if (y < 2 || y >= sizeY - 6) {
                    continue;
                }
                int worldX = worldPosX + x;
                int worldZ = worldPosY + z;
                float rugged = ruggednessMap[x][z];
                int spacing = 11 + (hash(worldX, worldZ, preset, 33) % 7); // 11..17
                int cellX = Math.floorDiv(worldX, spacing);
                int cellZ = Math.floorDiv(worldZ, spacing);
                int slotX = Math.floorMod(worldX, spacing);
                int slotZ = Math.floorMod(worldZ, spacing);
                int targetX = hash(cellX, cellZ, preset, 91) % spacing;
                int targetZ = hash(cellZ, cellX, preset, 113) % spacing;
                if (slotX != targetX || slotZ != targetZ) {
                    continue;
                }
                float chance = 0.26f + rugged * 0.44f;
                float roll = (hash(worldX, worldZ, y, 157) & 0xFFFF) / 65535.0f;
                if (roll > chance) {
                    continue;
                }
                if (!isGroundLocallyFlat(heightMap, x, z, 3)) {
                    continue;
                }

                int radius = 2 + hash(worldX, worldZ, y, 201) % 3; // 2..4
                int cap = radius - 1 + hash(worldX, worldZ, y, 233) % 2; // tighter dome
                int blockA = boulderBlockForPreset(preset, worldX, worldZ, 0);
                int blockB = boulderBlockForPreset(preset, worldX, worldZ, 1);
                highest = Math.max(highest, placeBoulderBlob(data, x, y, z, radius, cap, blockA, blockB));
            }
        }
        return highest;
    }

    private static int boulderBlockForPreset(int preset, int worldX, int worldZ, int variant) {
        int h = hash(worldX, worldZ, preset, 311 + variant * 17) % 100;
        if (preset == WorldPreset.MARS) {
            return h < 72 ? Block.BASALT_BOULDER : Block.RED_SANDSTONE;
        }
        if (preset == WorldPreset.VENUS) {
            return h < 72 ? Block.SULFUR_BOULDER : Block.BASALT_BOULDER;
        }
        return h < 78 ? Block.FROST_BOULDER : Block.SULFUR_BOULDER;
    }

    private static int placeBoulderBlob(int[][][] data, int cx, int baseY, int cz, int radius, int cap,
                                        int primary, int accent) {
        int topY = baseY + 1;
        int maxY = Math.max(radius + 1, cap);
        float flatten = 0.72f;
        for (int dy = -1; dy <= maxY; dy++) {
            int y = baseY + dy;
            if (y <= 1 || y >= sizeY - 1) {
                continue;
            }
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int x = cx + dx;
                    int z = cz + dz;
                    if (x < 1 || x >= sizeX - 1 || z < 1 || z >= sizeZ - 1) {
                        continue;
                    }
                    float nx = dx / (float) radius;
                    float ny = dy / (radius * flatten);
                    float nz = dz / (float) radius;
                    float radial = nx * nx + ny * ny + nz * nz;
                    float shellNoise = ((hash(x, y, z, 419) & 0xFFFF) / 65535.0f - 0.5f) * 0.46f;
                    float threshold = 0.92f + shellNoise;
                    if (radial > threshold) {
                        continue;
                    }
                    if (dy > 0 && data[x][y][z] != Block.AIR) {
                        continue;
                    }
                    int type = (radial > threshold - 0.16f && ((hash(x, y, z, 503) % 100) < 26))
                            ? accent : primary;
                    data[x][y][z] = type;
                    if (y > topY) {
                        topY = y;
                    }
                }
            }
        }
        return topY + 1;
    }

    private static int surfaceTypeFor(int height, BiomeBlend biome, float ruggedness,
                                      float temperature, float highlands, float wetland,
                                      int worldX, int worldZ, int biomeType) {
        float desertW = biome.weight(BiomeDefinition.DESERT);
        float tundraW = biome.weight(BiomeDefinition.TUNDRA);
        float forestW = biome.weight(BiomeDefinition.FOREST);
        float grassyW = biome.weight(BiomeDefinition.GRASSY);
        boolean desertDominant = biome.dominantBiome() == BiomeDefinition.DESERT;
        boolean tundraDominant = biome.dominantBiome() == BiomeDefinition.TUNDRA;
        if (height < SEA_LEVEL) {
            return desertW > 0.55f ? Block.RED_SAND : Block.SAND;
        }
        if (height <= SEA_LEVEL + 2) {
            return desertW > 0.55f ? Block.RED_SAND : Block.SAND;
        }
        if (biomeType == EarthBiome.HOT_DESERT) {
            if (height >= 90 && ruggedness > 0.76f) {
                return Block.STONE;
            }
            return temperature > 0.72f ? Block.RED_SAND : Block.SAND;
        }
        if (biomeType == EarthBiome.WETLAND) {
            return wetland > 0.72f ? Block.CLAY : Block.MUD;
        }
        if (biomeType == EarthBiome.ALPINE) {
            return ruggedness > 0.66f ? Block.STONE : Block.SNOW;
        }
        if (wetland > 0.65f && height <= SEA_LEVEL + 8) {
            return Block.CLAY;
        }
        // Snow line tracks temperature and broad relief so snow stays altitude-
        // correlated instead of touching warm low-altitude surfaces.
        float snowline = lerp(108.0f, 74.0f, clamp01(1.0f - temperature));
        snowline -= highlands * 10.0f;
        // The classified biome is resolved before the surrounding climate
        // weights get a say. A column that dithered into a wooded neighbour
        // would otherwise be overridden back to sand or snow by the region it
        // sits in, which is what produced hard borders.
        if (biomeType == EarthBiome.SAVANNA) {
            return ruggedness > 0.68f ? Block.STONE : Block.DIRT;
        }
        if (biomeType == EarthBiome.SHRUBLAND) {
            return ruggedness > 0.58f ? Block.STONE : Block.DIRT;
        }
        if (biomeType == EarthBiome.TEMPERATE_GRASSLAND) {
            return Block.GRASS;
        }
        if (biomeType == EarthBiome.TEMPERATE_FOREST) {
            return wetland > 0.54f ? Block.MUD : Block.GRASS;
        }
        if (biomeType == EarthBiome.BOREAL_FOREST) {
            return height >= snowline - 1.0f ? Block.SNOW : Block.DIRT;
        }
        if (biomeType == EarthBiome.TROPICAL_RAINFOREST) {
            return wetland > 0.58f ? Block.MUD : Block.GRASS;
        }
        if (biomeType == EarthBiome.TUNDRA) {
            float thaw = sample01(worldX, worldZ, 95.0, 211, -67);
            if (height >= snowline + 5.0f || (height >= snowline && ruggedness > 0.62f)) {
                return Block.SNOW;
            }
            if (wetland > 0.50f && height <= SEA_LEVEL + 14) {
                return Block.SLUSH;
            }
            return thaw > 0.60f ? Block.MUD : Block.DIRT;
        }
        if (desertDominant) {
            if (height >= 92 && ruggedness > 0.78f) {
                return Block.STONE;
            }
            return temperature > 0.72f ? Block.RED_SAND : Block.SAND;
        }
        if (tundraDominant) {
            float thaw = sample01(worldX, worldZ, 95.0, 211, -67);
            if (height >= snowline + 5.0f || (height >= snowline && ruggedness > 0.62f)) {
                return Block.SNOW;
            }
            if (wetland > 0.50f && height <= SEA_LEVEL + 14) {
                return Block.SLUSH;
            }
            if (thaw > 0.60f) {
                return Block.MUD;
            }
            return Block.DIRT;
        }
        if (tundraW > 0.44f && wetland > 0.56f && height <= SEA_LEVEL + 10) {
            return Block.SLUSH;
        }
        if (tundraW > 0.48f) {
            return height >= snowline - 2.0f ? Block.SNOW : Block.DIRT;
        }
        if (desertW > 0.52f) {
            return Block.RED_SAND;
        }
        if (height >= snowline && ruggedness > 0.40f) {
            return Block.SNOW;
        }
        if (height >= 86 && ruggedness > 0.70f) {
            return Block.STONE;
        }
        if (forestW > 0.45f || grassyW > 0.35f) {
            return Block.GRASS;
        }
        return Block.GRASS;
    }

    private static int fillerTypeFor(int surface, BiomeBlend biome, int y, int height,
                                     int worldX, int worldZ, float wetland) {
        int depth = height - y;
        if (surface == Block.SAND) {
            return depth >= 3 ? Block.SANDSTONE : Block.SAND;
        }
        if (surface == Block.RED_SAND) {
            return depth >= 3 ? Block.RED_SANDSTONE : Block.RED_SAND;
        }
        if (surface == Block.CLAY) {
            return Block.CLAY;
        }
        if (surface == Block.MUD) {
            return depth >= 3 ? Block.DIRT : Block.MUD;
        }
        if (surface == Block.SLUSH) {
            if (depth <= 1) {
                return Block.SLUSH;
            }
            return depth <= 3 ? Block.MUD : Block.DIRT;
        }
        if (surface == Block.SNOW && biome.weight(BiomeDefinition.TUNDRA) > 0.40f) {
            return depth <= 1 ? Block.SNOW : (depth <= 3 ? Block.SLUSH : Block.DIRT);
        }
        if (surface == Block.STONE) {
            return Block.ANDESITE;
        }
        // Damp, low columns can lay clay under shallow lake water.
        if (wetland > 0.48f || height <= SEA_LEVEL + 2) {
            float wetness = (float) ((PerlinNoiseGenerator.getNoise((worldX - 41) / 170.0, (worldZ + 23) / 170.0) + 1.0) * 0.5);
            if (wetness > 0.65f && depth <= 5) {
                return Block.CLAY;
            }
        }
        return Block.DIRT;
    }

    private static int geologyTypeFor(int y, int worldX, int worldZ) {
        if (y < 10) {
            return Block.DEEPSLATE;
        }
        float a = (float) PerlinNoiseGenerator.getNoise(worldX / 96.0, (worldZ + y * 2) / 96.0);
        if (a > 0.40f) {
            return Block.GRANITE;
        }
        if (a > 0.14f) {
            return Block.DIORITE;
        }
        if (a > -0.08f) {
            return Block.ANDESITE;
        }
        float damp = (float) ((PerlinNoiseGenerator.getNoise((worldX - 79) / 210.0, (worldZ + 53) / 210.0) + 1.0) * 0.5);
        if (damp > 0.76f && y < SEA_LEVEL + 12) {
            return Block.MOSSY_COBBLESTONE;
        }
        return Block.STONE;
    }

    private int plantVegetation(int[][][] data,
                                int[][] heightMap,
                                int[][] surfaceMap,
                                int[][] biomeTypeMap,
                                float[][] biomeBorderMap,
                                float[][] tundraWeightMap,
                                float[][] desertWeightMap,
                                float[][] forestWeightMap,
                                float[][] grassyWeightMap,
                                float[][] wetlandMap,
                                int highest) {
        for (int x = 1; x < sizeX - 1; x++) {
            for (int z = 1; z < sizeZ - 1; z++) {
                int y = heightMap[x][z];
                if (y < 1 || y >= sizeY - 2) {
                    continue;
                }
                int surface = surfaceMap[x][z];
                if (surface != Block.GRASS && surface != Block.CLAY && surface != Block.SNOW
                        && surface != Block.DIRT && surface != Block.MUD && surface != Block.SLUSH) {
                    continue;
                }
                if (data[x][y + 1][z] != Block.AIR) {
                    continue;
                }
                // Keep plants away from chunk borders so decoration does not
                // depend on neighbour load order.
                if (x < 2 || x > sizeX - 3 || z < 2 || z > sizeZ - 3) {
                    continue;
                }

                int wx = worldPosX + x;
                int wz = worldPosY + z;
                float tundraW = tundraWeightMap[x][z];
                float desertW = desertWeightMap[x][z];
                float forestW = forestWeightMap[x][z];
                float grassyW = grassyWeightMap[x][z];
                float wet = wetlandMap[x][z];
                int biomeType = biomeTypeMap[x][z];
                float border = biomeBorderMap[x][z];
                if (desertW > lerp(0.42f, 0.70f, border)) {
                    continue;
                }
                int h = hash(wx, wz, y, 911);

                float plantChance = 0.05f + forestW * 0.22f + grassyW * 0.16f + wet * 0.22f;
                // Ground cover follows the dithered biome, so a contested column
                // is not still suppressed by the climate weights around it.
                plantChance = lerp(plantChance, Math.max(plantChance, 0.16f), border);
                plantChance *= (1.0f - tundraW * lerp(0.72f, 0.34f, border));
                plantChance *= (1.0f - desertW * lerp(0.95f, 0.55f, border));
                if (biomeType == EarthBiome.TROPICAL_RAINFOREST) {
                    plantChance *= 1.38f;
                } else if (biomeType == EarthBiome.SAVANNA || biomeType == EarthBiome.BOREAL_FOREST) {
                    plantChance *= 0.82f;
                } else if (biomeType == EarthBiome.ALPINE || biomeType == EarthBiome.SHRUBLAND) {
                    plantChance *= 0.56f;
                }
                if (((h & 0xFFFF) / 65535.0f) > plantChance) {
                    continue;
                }

                int type;
                int pick = (h >>> 16) & 0xFF;
                switch (biomeType) {
                    case EarthBiome.TUNDRA -> type = pick < 86 ? Block.BROWN_GRASS : (pick < 96 ? Block.MUSHROOM : Block.TALL_GRASS);
                    case EarthBiome.BOREAL_FOREST -> type = pick < 72 ? Block.BROWN_GRASS : (pick < 92 ? Block.MUSHROOM : Block.TALL_GRASS);
                    case EarthBiome.SAVANNA -> type = pick < 80 ? Block.BROWN_GRASS : (pick < 94 ? Block.TALL_GRASS : Block.FLOWER);
                    case EarthBiome.SHRUBLAND -> type = pick < 74 ? Block.BROWN_GRASS : (pick < 92 ? Block.MUSHROOM : Block.FLOWER);
                    case EarthBiome.TROPICAL_RAINFOREST -> type = pick < 66 ? Block.TALL_GRASS : (pick < 86 ? Block.FLOWER : Block.MUSHROOM);
                    case EarthBiome.WETLAND -> type = pick < 70 ? Block.TALL_GRASS : (pick < 82 ? Block.FLOWER : Block.MUSHROOM);
                    case EarthBiome.ALPINE -> type = pick < 88 ? Block.BROWN_GRASS : Block.MUSHROOM;
                    default -> {
                        if (wet > 0.62f) {
                            type = pick < 74 ? Block.TALL_GRASS : (pick < 90 ? Block.FLOWER : Block.MUSHROOM);
                        } else if (forestW > 0.58f) {
                            type = pick < 62 ? Block.TALL_GRASS : (pick < 82 ? Block.FLOWER : Block.MUSHROOM);
                        } else {
                            type = pick < 76 ? Block.TALL_GRASS : Block.FLOWER;
                        }
                    }
                }
                if (surface == Block.SNOW && type == Block.FLOWER) {
                    type = tundraW > 0.45f ? Block.BROWN_GRASS : Block.TALL_GRASS;
                }

                data[x][y + 1][z] = type;
                if (y + 2 > highest) {
                    highest = y + 2;
                }
            }
        }
        return highest;
    }

    /**
     * Deterministic tree pass over generated terrain.
     *
     * Trees are constrained to interior columns so canopies do not depend on
     * neighbouring chunks that may not be generated yet.
     */
    private int plantTrees(int[][][] data,
                           int[][] heightMap,
                           int[][] surfaceMap,
                           int[][] biomeTypeMap,
                           float[][] biomeBorderMap,
                           float[][] tundraWeightMap,
                           float[][] desertWeightMap,
                           float[][] forestWeightMap,
                           float[][] grassyWeightMap,
                           float[][] ruggednessMap,
                           float[][] wetlandMap,
                           int highest) {
        for (int x = 2; x < sizeX - 2; x++) {
            for (int z = 2; z < sizeZ - 2; z++) {
                int y = heightMap[x][z];
                int surface = surfaceMap[x][z];
                if ((surface != Block.GRASS && surface != Block.DIRT && surface != Block.MUD)
                        || y <= SEA_LEVEL + 1 || y >= sizeY - 10) {
                    continue;
                }

                int biomeType = biomeTypeMap[x][z];
                float forestW = forestWeightMap[x][z];
                float grassyW = grassyWeightMap[x][z];
                float tundraW = tundraWeightMap[x][z];
                float desertW = desertWeightMap[x][z];
                float ruggedness = ruggednessMap[x][z];
                float wetland = wetlandMap[x][z];
                // The column already carries a dithered biome, so the coarse
                // climate weights only veto the deep interior of a hostile
                // region; near a border the dithered biome decides.
                float border = biomeBorderMap[x][z];
                if (desertW > lerp(0.35f, 0.68f, border) || tundraW > lerp(0.48f, 0.78f, border)) {
                    continue;
                }
                float biomeTreeFactor = treeDensityFactorForBiome(biomeType);
                if (biomeTreeFactor <= 0.01f) {
                    continue;
                }
                if (!shouldPlantTree(worldPosX + x, worldPosY + z, forestW, grassyW, ruggedness, wetland, biomeTreeFactor, border)) {
                    continue;
                }
                if (!isGroundLocallyFlat(heightMap, x, z, 2)) {
                    continue;
                }

                int style = hash(worldPosX + x, y, worldPosY + z, 71) % 4;
                int trunk = 4 + hash(worldPosX + x, y, worldPosY + z, 173) % 4; // 4..7
                int crownR = 1 + hash(worldPosX + x, y, worldPosY + z, 241) % 2; // 1..2
                highest = Math.max(highest, placeTree(data, x, y, z, trunk, crownR, style));
            }
        }
        return highest;
    }

    private static boolean shouldPlantTree(int worldX, int worldZ, float forestW, float grassyW,
                                           float ruggedness, float wetland, float biomeTreeFactor,
                                           float border) {
        float suitability = (forestW * 1.05f + grassyW * 0.55f - ruggedness * 0.35f - wetland * 0.22f) * biomeTreeFactor;
        // A column that dithered into a wooded neighbour still sits in the old
        // biome's climate weights, so without this its trees never take.
        suitability += border * 0.42f * biomeTreeFactor;
        if (suitability <= 0.30f) {
            return false;
        }
        int spacing = 10 + Math.max(0, (int) ((1.0f - suitability) * 10.0f)); // 10..20
        int cellX = Math.floorDiv(worldX, spacing);
        int cellZ = Math.floorDiv(worldZ, spacing);
        int slotX = Math.floorMod(worldX, spacing);
        int slotZ = Math.floorMod(worldZ, spacing);
        int targetX = hash(cellX, cellZ, spacing, 11) % spacing;
        int targetZ = hash(cellZ, cellX, spacing, 23) % spacing;
        // One candidate per spacing cell gives broad distribution without clumps.
        if (slotX != targetX || slotZ != targetZ) {
            return false;
        }
        float chance = 0.38f + suitability * 0.56f;
        float roll = (hash(worldX, worldZ, spacing, 37) & 0xFFFF) / 65535.0f;
        return roll < chance;
    }

    private static float treeDensityFactorForBiome(int biomeType) {
        return switch (biomeType) {
            case EarthBiome.HOT_DESERT -> 0.0f;
            case EarthBiome.SAVANNA -> 0.38f;
            case EarthBiome.SHRUBLAND -> 0.28f;
            case EarthBiome.TEMPERATE_GRASSLAND -> 0.16f;
            case EarthBiome.TEMPERATE_FOREST -> 1.00f;
            case EarthBiome.BOREAL_FOREST -> 0.82f;
            case EarthBiome.TUNDRA -> 0.06f;
            case EarthBiome.TROPICAL_RAINFOREST -> 1.22f;
            case EarthBiome.WETLAND -> 0.40f;
            case EarthBiome.ALPINE -> 0.08f;
            default -> 0.55f;
        };
    }

    private static boolean isGroundLocallyFlat(int[][] heightMap, int x, int z, int tolerance) {
        int h = heightMap[x][z];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (Math.abs(heightMap[x + dx][z + dz] - h) > tolerance) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int placeTree(int[][][] data, int x, int groundY, int z,
                                 int trunkHeight, int crownRadius, int style) {
        int topY = Math.min(sizeY - 2, groundY + trunkHeight);
        for (int y = groundY + 1; y <= topY; y++) {
            data[x][y][z] = Block.WOOD;
        }

        int crownCenterY = Math.min(sizeY - 2, topY);
        int maxPlacedY = topY;
        int lower = (style == 2) ? -2 : -1;
        int upper = (style == 1) ? 2 : 1;
        for (int y = crownCenterY + lower; y <= crownCenterY + upper; y++) {
            if (y < 1 || y >= sizeY - 1) {
                continue;
            }
            int r = crownRadius;
            if (style == 0) {
                r = (y >= crownCenterY) ? Math.max(1, crownRadius - 1) : crownRadius;
            } else if (style == 1) {
                r = (y == crownCenterY + 2) ? 1 : crownRadius + 1;
            } else if (style == 3) {
                r = (y == crownCenterY + 1) ? 1 : crownRadius;
            } else {
                r = (y <= crownCenterY - 1) ? crownRadius + 1 : crownRadius;
            }
            if (r < 1) {
                r = 1;
            }
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + 1) {
                        continue;
                    }
                    if (style == 3 && ((hash(x + dx, y, z + dz, 99) % 100) < 28)) {
                        continue;
                    }
                    int px = x + dx;
                    int pz = z + dz;
                    if (px < 0 || px >= sizeX || pz < 0 || pz >= sizeZ) {
                        continue;
                    }
                    if (data[px][y][pz] == Block.AIR || data[px][y][pz] == Block.LEAVES) {
                        data[px][y][pz] = Block.LEAVES;
                        if (y > maxPlacedY) {
                            maxPlacedY = y;
                        }
                    }
                }
            }
        }
        if (topY + 1 < sizeY - 1 && data[x][topY + 1][z] == Block.AIR) {
            data[x][topY + 1][z] = Block.LEAVES;
            maxPlacedY = Math.max(maxPlacedY, topY + 1);
        }
        // Occasional side branches for variety.
        if (style == 1 || style == 2) {
            int by = groundY + 2 + hash(x, z, trunkHeight, 58) % Math.max(1, trunkHeight - 1);
            if (by < topY - 1) {
                int dir = hash(x, z, groundY, 121) % 4;
                int bx = x + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
                int bz = z + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                if (bx > 0 && bx < sizeX - 1 && bz > 0 && bz < sizeZ - 1) {
                    data[bx][by][bz] = Block.WOOD;
                    if (data[bx][by + 1][bz] == Block.AIR) {
                        data[bx][by + 1][bz] = Block.LEAVES;
                        maxPlacedY = Math.max(maxPlacedY, by + 1);
                    }
                }
            }
        }
        return maxPlacedY + 1;
    }

    /**
     * Packed biome tint at a world column.
     *
     * Deliberately a pure function of world coordinates and the seed: neighbouring
     * chunks compute the same value at a shared edge, so the colour is continuous
     * across chunk borders with no need to consult the neighbour's data.
     *
     * The weights here are softer than the ones driving terrain height, because a
     * colour gradient reads better spread over a wider band than a shape change.
     */
    public static float biomeTintAt(int worldX, int worldZ) {
        if (WorldPreset.clamp(World.WORLD_PRESET) != WorldPreset.EARTH) {
            return Block.NO_TINT;
        }
        float[] rgb = new float[3];
        biomeTintRgbAt(worldX, worldZ, rgb);
        return Block.packTint(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * How far foliage and soil follow the biome colour.
     *
     * Soil is held well back: real ground colour varies far less between biomes
     * than leaf colour does, and the terrain already signals biome through the
     * block type it picks.
     */
    private static final float FOLIAGE_TINT_STRENGTH = 0.45f;
    private static final float GROUND_TINT_STRENGTH = 0.20f;

    private static void biomeTintRgbAt(int worldX, int worldZ, float[] out) {
        double wx = warpedX(worldX, worldZ);
        double wz = warpedZ(worldX, worldZ);
        float temperature = sample01(wx, wz, 230.0, 0, 0);
        float moisture = sample01(wx, wz, 240.0, 137, -89);
        temperature = clamp01(temperature + (sample01(wx, wz, 120.0, 311, -173) - 0.5f) * 0.26f);
        moisture = clamp01(moisture + (sample01(wx, wz, 130.0, -421, 257) - 0.5f) * 0.30f);
        float ruggedness = sample01(wx, wz, 560.0, -211, 173);
        float macro = sample01(wx, wz, 1800.0, 73, -511);
        float mountainRegion = sample01(wx, wz, 1350.0, -733, 419);

        float highlands = smoothstep(0.54f, 0.80f, macro) * smoothstep(0.50f, 0.82f, mountainRegion);
        float lowlands = (1.0f - smoothstep(0.18f, 0.36f, macro))
                * (1.0f - smoothstep(0.58f, 0.84f, mountainRegion));
        float mountainMask = smoothstep(0.50f, 0.86f, mountainRegion * 0.62f + highlands * 0.34f);

        float[] w = earthClimateWeights(temperature, moisture, ruggedness,
                highlands, lowlands, mountainMask, 1.7f);
        float r = 0f, g = 0f, b = 0f;
        for (int i = 0; i < w.length; i++) {
            r += w[i] * EarthBiome.TINT[i][0];
            g += w[i] * EarthBiome.TINT[i][1];
            b += w[i] * EarthBiome.TINT[i][2];
        }
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    /**
     * Tint sampled at the chunk's block corners, so a vertex can look it up
     * directly and let the rasteriser interpolate between them.
     */
    private void buildTintGrid() {
        int cells = (sizeX + 1) * (sizeZ + 1);
        if (tintGrid == null || tintGrid.length != cells) {
            tintGrid = new float[cells];
            groundTintGrid = new float[cells];
        }
        boolean earth = WorldPreset.clamp(World.WORLD_PRESET) == WorldPreset.EARTH;
        float[] rgb = new float[3];
        for (int cx = 0; cx <= sizeX; cx++) {
            for (int cz = 0; cz <= sizeZ; cz++) {
                int idx = cx * (sizeZ + 1) + cz;
                if (!earth) {
                    tintGrid[idx] = Block.NO_TINT;
                    groundTintGrid[idx] = Block.NO_TINT;
                    continue;
                }
                biomeTintRgbAt(worldPosX + cx, worldPosY + cz, rgb);
                tintGrid[idx] = Block.packTint(
                        lerp(1.0f, rgb[0], FOLIAGE_TINT_STRENGTH),
                        lerp(1.0f, rgb[1], FOLIAGE_TINT_STRENGTH),
                        lerp(1.0f, rgb[2], FOLIAGE_TINT_STRENGTH));
                // Soil shifts with climate too, but far less than leaves do, so
                // the same field is pulled back toward neutral for ground blocks.
                groundTintGrid[idx] = Block.packTint(
                        lerp(1.0f, rgb[0], GROUND_TINT_STRENGTH),
                        lerp(1.0f, rgb[1], GROUND_TINT_STRENGTH),
                        lerp(1.0f, rgb[2], GROUND_TINT_STRENGTH));
            }
        }
    }

    @Override
    public float tintAt(int cornerX, int cornerZ, boolean ground) {
        float[] grid = ground ? groundTintGrid : tintGrid;
        if (grid == null) {
            return Block.NO_TINT;
        }
        int cx = Math.max(0, Math.min(sizeX, cornerX));
        int cz = Math.max(0, Math.min(sizeZ, cornerZ));
        return grid[cx * (sizeZ + 1) + cz];
    }

    private static int hash(int a, int b, int c, int salt) {
        int h = a * 73856093 ^ b * 19349663 ^ c * 83492791 ^ salt * 374761393;
        h ^= (h >>> 13);
        h *= 1274126177;
        h ^= (h >>> 16);
        return h & 0x7fffffff;
    }

    /** Human-readable biome label for HUD/window title. */
    public static String biomeLabelAt(int worldX, int worldZ) {
        int worldPreset = WorldPreset.clamp(World.WORLD_PRESET);
        double wx = warpedX(worldX, worldZ);
        double wz = warpedZ(worldX, worldZ);
        float temperature = sample01(wx, wz, 230.0, 0, 0);
        float moisture = sample01(wx, wz, 240.0, 137, -89);
        temperature = clamp01(temperature + (sample01(wx, wz, 120.0, 311, -173) - 0.5f) * 0.26f);
        moisture = clamp01(moisture + (sample01(wx, wz, 130.0, -421, 257) - 0.5f) * 0.30f);
        float ruggedness = sample01(wx, wz, 560.0, -211, 173);
        float macro = sample01(wx, wz, 1800.0, 73, -511);
        float mountainRegion = sample01(wx, wz, 1350.0, -733, 419);

        BiomeBlend blend = blendBiomes(temperature, moisture, wx, wz);
        float highlands = smoothstep(0.54f, 0.80f, macro) * smoothstep(0.50f, 0.82f, mountainRegion);
        float lowlands = (1.0f - smoothstep(0.18f, 0.36f, macro))
                * (1.0f - smoothstep(0.58f, 0.84f, mountainRegion));
        int height = (int) World.getHeightAt(worldX, worldZ);
        float wetland = clamp01((moisture - 0.58f) * 2.4f)
                * clamp01((SEA_LEVEL + 10 - height) / 14.0f)
                * clamp01(1.0f - ruggedness * 1.1f)
                * lowlands;
        String base;
        if (worldPreset == WorldPreset.EARTH) {
            int biomeType = classifyEarthBiome(temperature, moisture, ruggedness, height, wetland, blend);
            base = EarthBiome.nameOf(biomeType);
        } else if (worldPreset == WorldPreset.MARS) {
            base = temperature > 0.64f ? "Martian Dune Field" : "Martian Basalt Plains";
        } else if (worldPreset == WorldPreset.VENUS) {
            base = ruggedness > 0.58f ? "Venusian Highlands" : "Venusian Lava Plains";
        } else {
            base = wetland > 0.55f ? "Triton Ice Basin" : "Triton Cryo Plains";
        }
        if (highlands > 0.60f) {
            return base + " Highlands";
        }
        if (lowlands > 0.55f) {
            return base + " Lowlands";
        }
        return base;
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
            lightQueue = new int[1 << 16];
        }
        queueHead = 0;
        queueTail = 0;

        // Seed straight down from open sky.
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = sizeY - 1; y >= 0; y--) {
                    if (!Block.transmitsLight(blocks[x][y][z])) {
                        break;
                    }
                    light[lightIndex(x, y, z)] = (byte) MAX_LIGHT;
                    push(x, y, z);
                }
            }
        }

        // Seed the four borders from neighbouring chunks that are already lit.
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                seedBorder(light, x, y, 0, worldPosX + x, y, worldPosY - 1);
                seedBorder(light, x, y, sizeZ - 1, worldPosX + x, y, worldPosY + sizeZ);
            }
            for (int z = 0; z < sizeZ; z++) {
                seedBorder(light, 0, y, z, worldPosX - 1, y, worldPosY + z);
                seedBorder(light, sizeX - 1, y, z, worldPosX + sizeX, y, worldPosY + z);
            }
        }

        while (queueHead < queueTail) {
            int packed = lightQueue[queueHead++];
            int x = (packed >> 11) & 0x1F;
            int y = (packed >> 4) & 0x7F;
            int z = packed & 0xF;
            int level = light[lightIndex(x, y, z)];
            if (level <= 1) {
                continue;
            }
            spread(light, x + 1, y, z, level);
            spread(light, x - 1, y, z, level);
            spread(light, x, y + 1, z, level);
            spread(light, x, y - 1, z, level);
            spread(light, x, y, z + 1, level);
            spread(light, x, y, z - 1, level);
        }

        publishBorderChanges(light);
    }

    /**
     * Appends to the BFS queue, growing it as needed.
     *
     * A cell is enqueued each time its level improves, so the queue holds far
     * more entries than there are voxels. The previous fixed-size queue silently
     * abandoned propagation once full, leaving unlit patches.
     */
    private void push(int x, int y, int z) {
        if (queueTail == lightQueue.length) {
            if (queueHead > 0) {
                System.arraycopy(lightQueue, queueHead, lightQueue, 0, queueTail - queueHead);
                queueTail -= queueHead;
                queueHead = 0;
            } else {
                lightQueue = java.util.Arrays.copyOf(lightQueue, lightQueue.length * 2);
            }
        }
        lightQueue[queueTail++] = (x << 11) | (y << 4) | z;
    }

    private static final int BORDER_STRIDE = sizeY * sizeZ;

    /**
     * Flags neighbours whose seeded border light is now out of date.
     *
     * Without this, digging a tunnel up to a chunk edge lights only the chunk
     * that changed; the neighbour keeps the light it computed before the tunnel
     * existed, leaving a hard dark seam until something else forces it to
     * rebuild.
     */
    private void publishBorderChanges(byte[] light) {
        boolean first = borderSnapshot == null;
        if (first) {
            borderSnapshot = new byte[4 * BORDER_STRIDE];
        }

        boolean changedXLow = first, changedXHigh = first;
        boolean changedZLow = first, changedZHigh = first;

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                int slot = y * sizeZ + z;
                byte low = light[lightIndex(0, y, z)];
                if (borderSnapshot[slot] != low) {
                    borderSnapshot[slot] = low;
                    changedXLow = true;
                }
                byte high = light[lightIndex(sizeX - 1, y, z)];
                if (borderSnapshot[BORDER_STRIDE + slot] != high) {
                    borderSnapshot[BORDER_STRIDE + slot] = high;
                    changedXHigh = true;
                }
            }
            for (int x = 0; x < sizeX; x++) {
                int slot = y * sizeX + x;
                byte low = light[lightIndex(x, y, 0)];
                if (borderSnapshot[2 * BORDER_STRIDE + slot] != low) {
                    borderSnapshot[2 * BORDER_STRIDE + slot] = low;
                    changedZLow = true;
                }
                byte high = light[lightIndex(x, y, sizeZ - 1)];
                if (borderSnapshot[3 * BORDER_STRIDE + slot] != high) {
                    borderSnapshot[3 * BORDER_STRIDE + slot] = high;
                    changedZHigh = true;
                }
            }
        }

        if (changedXLow) {
            markNeighborStale(posX - 1, posY);
        }
        if (changedXHigh) {
            markNeighborStale(posX + 1, posY);
        }
        if (changedZLow) {
            markNeighborStale(posX, posY - 1);
        }
        if (changedZHigh) {
            markNeighborStale(posX, posY + 1);
        }
    }

    /**
     * Only chunks that have already been lit are flagged. A chunk with no light
     * yet will seed from this one when it first builds, so flagging it would just
     * duplicate work during world generation.
     */
    private void markNeighborStale(int chunkX, int chunkZ) {
        WorldChunk neighbor = World.getChunk(chunkX, chunkZ);
        if (neighbor != null && neighbor.isGenerated && neighbor.skyLight != null) {
            neighbor.meshIsStale = true;
        }
    }

    private void seedBorder(byte[] light, int x, int y, int z,
                            int worldX, int worldY, int worldZ) {
        if (!Block.transmitsLight(blocks[x][y][z])) {
            return;
        }
        int level = World.skyLightGlobal(worldX, worldY, worldZ) - 1;
        int idx = lightIndex(x, y, z);
        if (level > light[idx]) {
            light[idx] = (byte) level;
            push(x, y, z);
        }
    }

    private void spread(byte[] light, int x, int y, int z, int level) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return;
        }
        int type = blocks[x][y][z];
        if (!Block.transmitsLight(type)) {
            return;
        }
        int idx = lightIndex(x, y, z);
        int next = (byte) Math.max(0, level - (Block.lightCost(type) + (MAX_LIGHT - level) / 8));
        if (next > light[idx]) {
            light[idx] = (byte) next;
            push(x, y, z);
        }
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
            buildTintGrid();
            int ceiling = Math.min(this.maxHeight, sizeY);

            // Pass 1: count exposed faces so the chunk buffer can be sized exactly.
            // Nothing is allocated per block, unlike the old FloatBuffer-per-cube
            // approach that then had to be merged into a second buffer.
            int faceCount = 0;
            int blockCount = 0;
            for (int i = 0; i < sizeX; i++) {
                for (int j = 0; j < ceiling; j++) {
                    for (int k = 0; k < sizeZ; k++) {
                        int type = blocks[i][j][k];
                        if (type != 0) {
                            if (Block.isSpritePlant(type)) {
                                faceCount += 4; // two crossed quads, double sided
                            } else if (Block.isMarchingRock(type)) {
                                if (computeExposedFaces(i, j, k, EXPOSED_FACES) > 0) {
                                    faceCount += 8; // centered closed rock mesh (octahedron)
                                }
                            } else {
                                faceCount += computeExposedFaces(i, j, k, EXPOSED_FACES);
                            }
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
                        if (type != 0 && !Block.isTranslucent(type)) {
                            if (Block.isSpritePlant(type)) {
                                Block.writeCrossSprite(buffer, i, j, k, type, this);
                            } else if (Block.isMarchingRock(type) && computeExposedFaces(i, j, k, EXPOSED_FACES) > 0) {
                                Block.writeMarchingRock(buffer, i, j, k, EXPOSED_FACES, type, this);
                            } else if (computeExposedFaces(i, j, k, EXPOSED_FACES) > 0) {
                                Block.writeCube(buffer, i, j, k, EXPOSED_FACES, type, this);
                            }
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
        if (this.meshReadyAtNanos < 0) {
            // First time this chunk instance has ever had a mesh uploaded --
            // starts its fade-in. Never touched again by later rebuilds (block
            // edits, seam invalidation, etc.), which reuse the existing VBO
            // handle and never pass through this branch a second time.
            this.meshReadyAtNanos = System.nanoTime();
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
        // purgeVBO used to stay true forever after this ran, and isBuilt
        // stayed true too, so render()'s `if (purgeVBO) { deleteVBO(); return; }`
        // took this branch on every future frame and renderChunk()'s
        // `!isBuilt` rebuild check never re-armed -- a chunk that got purged
        // while still inside the keep radius (e.g. draw distance changing
        // mid-sweep) went permanently invisible even though its block data,
        // and so collision, was untouched. Resetting both here means the
        // normal isGenerated && !isBuilt path in World.renderChunk() picks it
        // straight back up and rebuilds it next time it is in view.
        this.purgeVBO = false;
        this.isBuilt = false;
        this.vboIsStale = false;
        this.meshReadyAtNanos = -1L;
        this.destroyRequestedAtNanos = -1L;
    }

    /**
     * Starts (once) this chunk's destroy fade-out clock. Idempotent -- the
     * sweeper re-detects the same out-of-range chunk on every sweep, and
     * restarting the clock each time would mean it never finishes fading and
     * so never actually gets torn down.
     *
     * Backdates the clock so the fade-out picks up from whatever alpha this
     * chunk is already showing (e.g. still partway through fading in) rather
     * than always assuming it was fully opaque -- otherwise a chunk leaving
     * view mid fade-in would visibly pop up to full brightness for an instant
     * before starting to fade back down.
     */
    public synchronized void requestDestroyFade() {
        if (this.destroyRequestedAtNanos < 0) {
            float currentAlpha = lifecycleFadeAlpha();
            long backdateNanos = (long) ((1.0f - currentAlpha) * fadeDurationNanos());
            this.destroyRequestedAtNanos = System.nanoTime() - backdateNanos;
        }
    }

    /**
     * Cancels an in-progress destroy fade, e.g. when the player walks back into
     * range before the chunk was actually swept and freed.
     *
     * Backdates meshReadyAtNanos so the fade-in resumes from whatever alpha the
     * fade-out had already reached, rather than snapping straight to fully
     * visible (a stale, long-elapsed meshReadyAtNanos from the original build)
     * or fully invisible -- either would be a visible pop when a chunk crosses
     * back and forth across the keep-radius boundary.
     */
    public synchronized void cancelDestroyFade() {
        if (this.destroyRequestedAtNanos < 0) {
            return;
        }
        float currentAlpha = lifecycleFadeAlpha();
        long backdateNanos = (long) (currentAlpha * fadeDurationNanos());
        this.destroyRequestedAtNanos = -1L;
        this.meshReadyAtNanos = System.nanoTime() - backdateNanos;
    }

    /** True once a requested destroy fade has fully played out -- only then is it
     *  safe to actually free this chunk's GPU resources. */
    public boolean isDestroyFadeComplete() {
        return this.destroyRequestedAtNanos >= 0
                && (System.nanoTime() - this.destroyRequestedAtNanos) >= fadeDurationNanos();
    }

    /**
     * Alpha driven purely by this chunk's own generate/destroy lifecycle (0-1),
     * independent of the distance-based edge fade computed in
     * World.renderChunk() -- the two are combined by multiplication there.
     * Fading out takes priority over fading in, since a chunk already queued
     * for destruction must never ramp back toward full opacity.
     */
    public float lifecycleFadeAlpha() {
        long now = System.nanoTime();
        long duration = fadeDurationNanos();
        if (this.destroyRequestedAtNanos >= 0) {
            float t = (now - this.destroyRequestedAtNanos) / (float) duration;
            return clamp01(1.0f - t);
        }
        if (this.meshReadyAtNanos >= 0) {
            float t = (now - this.meshReadyAtNanos) / (float) duration;
            return clamp01(t);
        }
        return 1.0f;
    }

    /**
     * Ensures the fade-in clock has started before this chunk's very first
     * draw call. World.renderChunk() computes this frame's renderAlpha
     * (which reads {@link #lifecycleFadeAlpha()}) BEFORE calling
     * {@link #render()} -- and render() is what actually calls
     * {@link #buildVBO()}, which is where meshReadyAtNanos first gets
     * stamped. Left alone, that ordering meant a chunk's first-ever frame on
     * screen always evaluated lifecycleFadeAlpha() while meshReadyAtNanos was
     * still -1 (not yet stamped this frame), falling through to the "not
     * tracked yet" 1.0 fallback -- so every newly built chunk rendered at
     * full opacity for exactly one frame before the fade-in formula ever
     * engaged, which is indistinguishable from "not fading in at all" when
     * chunks build faster than a human notices a single dropped frame (e.g.
     * flying forward into freshly generated terrain). Calling this first
     * closes that gap by stamping the clock proactively, so the very first
     * frame already reads a fresh (near-zero) alpha.
     */
    public void ensureFadeInStarted() {
        if (this.meshReadyAtNanos < 0) {
            this.meshReadyAtNanos = System.nanoTime();
        }
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
        // chunk is swept and regenerated from noise. An ungenerated chunk is never
        // written: its block array is still all air, and saving that would turn the
        // chunk into a void that reloads from disk forever.
        if (this.isGenerated && (this.isModified || Game.OPT_SAVE_CHUNKS)) {
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
        // Meshing and lighting index this array with the static chunk
        // dimensions, so anything else throws deep inside a worker thread.
        // Discard it and regenerate rather than let one stale file break
        // the chunk permanently.
        if (loaded.length != sizeX || loaded[0].length != sizeY
                || loaded[0][0].length != sizeZ) {
            System.err.println("Discarding chunk " + saveName() + " with unexpected size "
                    + loaded.length + "x" + loaded[0].length + "x" + loaded[0][0].length);
            Serializer.delete(saveName());
            return false;
        }
        this.blocks = loaded;
        return true;
    }

    private String saveName() {
        return this.posX + "-" + this.posY + ".chunk";
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
