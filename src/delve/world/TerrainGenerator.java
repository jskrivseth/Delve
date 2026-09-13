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
    private static final double CAVE_TUNNEL_SCALE_XZ = 46.0;
    private static final double CAVE_TUNNEL_SCALE_Y = 30.0;
    private static final double CAVE_DETAIL_SCALE = 15.0;
    private static final int CAVE_ENTRANCE_DEPTH = 11;

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
        int depthBelowSurface = surfaceY - y;
        if (depthBelowSurface <= CAVE_ENTRANCE_DEPTH
                && isErodedEntrance(worldX, y, worldZ, surfaceY)) {
            return true;
        }
        if (depthBelowSurface <= CAVE_SURFACE_BUFFER) {
            return false;
        }
        double depth = Math.min(1.0, Math.max(0.0, (depthBelowSurface - CAVE_SURFACE_BUFFER) / 72.0));
        return isTunnel(worldX, y, worldZ, 0.105 + depth * 0.020);
    }

    private static boolean isErodedEntrance(int worldX, int y, int worldZ, int surfaceY) {
        int depth = surfaceY - y;
        // A low-frequency gate separates entrance regions. Without this gate,
        // every zero crossing of the mouth field can breach the surface.
        double region = PerlinNoiseGenerator.getNoise(
                (worldX - 811) / 150.0,
                (worldZ + 613) / 150.0);
        if (region < 0.34) {
            return false;
        }
        // Require the funnel to meet a tunnel at depth instead of cutting an
        // isolated pit into otherwise solid terrain.
        int anchorY = surfaceY - CAVE_ENTRANCE_DEPTH;
        if (!isTunnel(worldX, anchorY, worldZ, 0.16)
                && !isTunnel(worldX, anchorY - 4, worldZ, 0.16)) {
            return false;
        }
        double broad = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX + 401) / 62.0,
                (surfaceY - 19) / 42.0,
                (worldZ - 337) / 62.0));
        double edge = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX - 149) / 21.0,
                (worldZ + 263) / 21.0));
        double aperture = 0.105 + depth * 0.019;
        return broad * 0.84 + edge * 0.16 < aperture;
    }

    /**
     * Intersecting two independently oriented zero-value noise sheets produces
     * winding tubes. A single thresholded noise volume produces the repeated
     * rounded chambers that this deliberately avoids.
     */
    private static boolean isTunnel(int worldX, int y, int worldZ, double radius) {
        double warp = PerlinNoiseGenerator.getNoise(
                (worldX + 719) / 104.0,
                (y - 283) / 76.0,
                (worldZ + 467) / 104.0) * 9.0;
        double firstSheet = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX + warp) / CAVE_TUNNEL_SCALE_XZ,
                (y - warp * 0.35) / CAVE_TUNNEL_SCALE_Y,
                (worldZ - warp) / CAVE_TUNNEL_SCALE_XZ));
        double secondSheet = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldZ + 353 - warp) / (CAVE_TUNNEL_SCALE_XZ * 1.12),
                (worldX - 127 + warp) / (CAVE_TUNNEL_SCALE_XZ * 1.12),
                (y + 211 + warp * 0.35) / (CAVE_TUNNEL_SCALE_Y * 1.12)));
        double detail = Math.abs(PerlinNoiseGenerator.getNoise(
                (worldX + 173) / CAVE_DETAIL_SCALE,
                (y - 67) / CAVE_DETAIL_SCALE,
                (worldZ - 251) / CAVE_DETAIL_SCALE));
        return Math.max(firstSheet, secondSheet) + detail * 0.08 < radius;
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
