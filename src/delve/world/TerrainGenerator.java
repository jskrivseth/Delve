/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package delve.world;

import java.util.Random;

import delve.core.Game;

/**
 *
 * @author Jesse
 */
public class TerrainGenerator {

    static final int CAVE_MIN_Y = 8;
    static final int CAVE_SURFACE_BUFFER = 4;
    private static final double CAVE_BODY_SCALE_XZ = 34.0;
    private static final double CAVE_BODY_SCALE_Y = 24.0;
    private static final double CAVE_DETAIL_SCALE_XZ = 13.0;
    private static final double CAVE_DETAIL_SCALE_Y = 10.0;
    private static final int CAVE_ENTRANCE_DEPTH = 9;

    /**
     * Tests a world-space cave voxel. Coordinates are absolute block
     * coordinates, so the same sample is used on both sides of chunk seams.
     * The surface buffer and minimum depth keep the surface silhouette and
     * foundation intact; water and bedrock are filtered by the caller.
     */
    static boolean isCave(int worldX, int y, int worldZ, int surfaceY) {
        if (y <= CAVE_MIN_Y || y > surfaceY) {
            return false;
        }
        double body = caveBody(worldX, y, worldZ);
        double detail = caveDetail(worldX, y, worldZ);
        int depthBelowSurface = surfaceY - y;
        if (depthBelowSurface <= CAVE_ENTRANCE_DEPTH
                && isErodedEntrance(worldX, y, worldZ, surfaceY)) {
            return true;
        }
        if (depthBelowSurface == 0) {
            return false;
        }
        // Taper the ordinary cave field through the last four underground
        // blocks. The broad component dominates here so entrances form useful
        // openings rather than isolated pinholes.
        if (depthBelowSurface <= CAVE_SURFACE_BUFFER) {
            double entranceThreshold = depthBelowSurface <= 2 ? 0.13 : 0.12;
            return body * 0.85 + detail * 0.15 < entranceThreshold;
        }
        double depth = Math.min(1.0, Math.max(0.0, (depthBelowSurface - CAVE_SURFACE_BUFFER) / 72.0));
        double threshold = 0.16 + depth * 0.08;
        return body * 0.72 + detail * 0.28 < threshold;
    }

    private static boolean isErodedEntrance(int worldX, int y, int worldZ, int surfaceY) {
        int depth = surfaceY - y;
        // Require an underground cave body beneath the mouth so erosion never
        // creates an isolated surface pit.
        double anchor = caveBody(worldX, surfaceY - CAVE_ENTRANCE_DEPTH, worldZ) * 0.72
                + caveDetail(worldX, surfaceY - CAVE_ENTRANCE_DEPTH, worldZ) * 0.28;
        if (anchor >= 0.23) {
            return false;
        }
        double broad = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX + 401) / 56.0,
                (surfaceY - 19) / 38.0,
                (worldZ - 337) / 56.0));
        double edge = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX - 149) / 21.0,
                (worldZ + 263) / 21.0));
        // Narrow at the exposed lip, widening rapidly below it. Fine noise
        // roughens the boundary so mouths read as eroded rock rather than tubes.
        double aperture = 0.13 + depth * 0.017;
        return broad * 0.82 + edge * 0.18 < aperture;
    }

    private static double caveBody(int worldX, int y, int worldZ) {
        return Math.abs(PerlinNoiseGenerator.getNoise(
                worldX / CAVE_BODY_SCALE_XZ,
                y / CAVE_BODY_SCALE_Y,
                worldZ / CAVE_BODY_SCALE_XZ));
    }

    private static double caveDetail(int worldX, int y, int worldZ) {
        return Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX + 173) / CAVE_DETAIL_SCALE_XZ,
                (y - 67) / CAVE_DETAIL_SCALE_Y,
                (worldZ - 251) / CAVE_DETAIL_SCALE_XZ));
    }

    static float[][] terrain;
    
    static float[][] generateWhiteNoise(int width, int height) {
        return generateWhiteNoise(width, height, World.getSeed());
    }

    static float[][] generateWhiteNoise(int width, int height, long seed) {
        Random random = new Random(seed); //Seed to 0 for testing
        float[][] noise = getEmptyArray(width, height);

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                noise[i][j] = (float) random.nextDouble() % 1;
            }
        }
        return noise;
    }

    static float[][] GenerateSmoothNoise(float[][] baseNoise, int octave) {
        int width = baseNoise.length;
        int height = baseNoise[0].length;

        float[][] smoothNoise = getEmptyArray(width, height);

        int samplePeriod = 1 << octave; // calculates 2 ^ k
        float sampleFrequency = 1.0f / samplePeriod;

        for (int i = 0; i < width; i++) {
            //calculate the horizontal sampling indices
            int sample_i0 = (i / samplePeriod) * samplePeriod;
            int sample_i1 = (sample_i0 + samplePeriod) % width; //wrap around
            float horizontal_blend = (i - sample_i0) * sampleFrequency;

            for (int j = 0; j < height; j++) {
                //calculate the vertical sampling indices
                int sample_j0 = (j / samplePeriod) * samplePeriod;
                int sample_j1 = (sample_j0 + samplePeriod) % height; //wrap around
                float vertical_blend = (j - sample_j0) * sampleFrequency;

                //blend the top two corners
                float top = Interpolate(baseNoise[sample_i0][sample_j0],
                        baseNoise[sample_i1][sample_j0], horizontal_blend);

                //blend the bottom two corners
                float bottom = Interpolate(baseNoise[sample_i0][sample_j1],
                        baseNoise[sample_i1][sample_j1], horizontal_blend);

                //final blend
                smoothNoise[i][j] = Interpolate(top, bottom, vertical_blend);
            }
        }

        return smoothNoise;
    }

    static float Interpolate(float x0, float x1, float alpha) {
        return x0 * (1 - alpha) + alpha * x1;
    }

    static float[][] GeneratePerlinNoise(float[][] baseNoise, int octaveCount) {
        int width = baseNoise.length;
        int height = baseNoise[0].length;

        float[][][] smoothNoise = new float[octaveCount][][]; //an array of 2D arrays containing

        float persistance = Game.WORLD_SMOOTHNESS;

        //generate smooth noise
        for (int i = 0; i < octaveCount; i++) {
            smoothNoise[i] = GenerateSmoothNoise(baseNoise, i);
        }

        float[][] perlinNoise = getEmptyArray(width, height);
        float amplitude = (float)Math.pow(WorldChunk.sizeY,18);
        float totalAmplitude = WorldChunk.sizeY/2;

        //blend noise together
        for (int octave = octaveCount - 1; octave >= 0; octave--) {
            amplitude *= persistance;
            totalAmplitude += amplitude;

            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) {
                    perlinNoise[i][j] += smoothNoise[octave][i][j] * amplitude;
                }
            }
        }

        //normalisation
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                perlinNoise[i][j] /= totalAmplitude;
            }
        }
        return perlinNoise;
    }

    public static float[][] getEmptyArray(int width, int height) {
        float[][] image = new float[width][];

        for (int i = 0; i < width; i++) {
            image[i] = new float[height];
        }
        return image;
    }
}
