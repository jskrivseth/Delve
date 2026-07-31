/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import java.util.Random;

public class PerlinNoiseGenerator extends NoiseGenerator {

    /**
     * Shared generator. Replaced by {@link #reseed(long)} when a world loads.
     *
     * This must be reseeded from the world seed rather than left at a random
     * default: the permutation table below defines the entire noise field, so a
     * random one means terrain regenerates differently on every launch and
     * saved chunks no longer line up with the world around them.
     */
    private static volatile PerlinNoiseGenerator instance = new PerlinNoiseGenerator(0L);

    protected PerlinNoiseGenerator() {
        this(new Random());
    }

    public PerlinNoiseGenerator(long seed) {
        this(new Random(seed));
    }

    /** Rebuilds the shared generator so a given seed always yields one world. */
    public static void reseed(long seed) {
        instance = new PerlinNoiseGenerator(seed);
    }

    public PerlinNoiseGenerator(Random rand) {
        offsetX = rand.nextDouble() * 256;
        offsetY = rand.nextDouble() * 256;
        offsetZ = rand.nextDouble() * 256;
        for (int i = 0; i < 256; i++) {
            perm[i] = rand.nextInt(256);
        }
        for (int i = 0; i < 256; i++) {
            int pos = rand.nextInt(256 - i) + i;
            int old = perm[i];
            perm[i] = perm[pos];
            perm[pos] = old;
            perm[i + 256] = perm[i];
        }
    }

    public static double getNoise(double x) {
        return instance.noise(x);
    }

    public static double getNoise(double x, double y) {
        return instance.noise(x, y);
    }

    public static double getNoise(double x, double y, double z) {
        return instance.noise(x, y, z);
    }

    public static PerlinNoiseGenerator getInstance() {
        return instance;
    }

    @Override
    public double noise(double x, double y, double z) {
        //System.out.println(x+","+y+","+z);
        x += offsetX;
        y += offsetY;
        z += offsetZ;
        int floorX = floor(x);
        int floorY = floor(y);
        int floorZ = floor(z);
// Find unit cube containing the point
        int X = floorX & 255;
        int Y = floorY & 255;
        int Z = floorZ & 255;
// Get relative xyz coordinates of the point within the cube
        x -= floorX;
        y -= floorY;
        z -= floorZ;
// Compute fade curves for xyz
        double fX = fade(x);
        double fY = fade(y);
        double fZ = fade(z);
// Hash coordinates of the cube corners
        int A = perm[X] + Y;
        int AA = perm[A] + Z;
        int AB = perm[A + 1] + Z;
        int B = perm[X + 1] + Y;
        int BA = perm[B] + Z;
        int BB = perm[B + 1] + Z;
        return lerp(fZ, lerp(fY, lerp(fX, grad(perm[AA], x, y, z),
                grad(perm[BA], x - 1, y, z)),
                lerp(fX, grad(perm[AB], x, y - 1, z),
                grad(perm[BB], x - 1, y - 1, z))),
                lerp(fY, lerp(fX, grad(perm[AA + 1], x, y, z - 1),
                grad(perm[BA + 1], x - 1, y, z - 1)),
                lerp(fX, grad(perm[AB + 1], x, y - 1, z - 1),
                grad(perm[BB + 1], x - 1, y - 1, z - 1))));
    }

    public static double getNoise(double x, int octaves, double frequency, double amplitude) {
        return instance.noise(x, octaves, frequency, amplitude);
    }

    public static double getNoise(double x, double y, int octaves, double frequency, double amplitude) {
        return instance.noise(x, y, octaves, frequency, amplitude);
    }

    public static double getNoise(double x, double y, double z, int octaves, double frequency, double amplitude) {
        return instance.noise(x, y, z, octaves, frequency, amplitude);
    }

    public static double getNoise(double x, double y, double z, int octaves, double frequency, double amplitude, boolean normalize) {
        return instance.noise(x, y, z, octaves, frequency, amplitude, normalize);
    }
}
