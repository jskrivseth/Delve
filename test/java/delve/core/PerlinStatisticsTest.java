package delve.core;

import delve.world.PerlinNoiseGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statistical coverage of seeded 3-D Perlin behaviour and shared noise math. */
public class PerlinStatisticsTest {

    private static final int GRID = 20;

    @Test
    public void testSameSeedReproducesAnIdenticalHeightmap() {
        PerlinNoiseGenerator a = new PerlinNoiseGenerator(7);
        PerlinNoiseGenerator b = new PerlinNoiseGenerator(7);
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                for (int gz = 0; gz < GRID; gz++) {
                    assertEquals(a.noise(gx, gy, gz), b.noise(gx, gy, gz), 1e-12, "deterministic field " + gx + "," + gy + "," + gz);
                }
            }
        }
    }

    @Test
    public void testDifferentSeedsDivergeEverywhere() {
        PerlinNoiseGenerator a = new PerlinNoiseGenerator(1);
        PerlinNoiseGenerator b = new PerlinNoiseGenerator(2);
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                for (int gz = 0; gz < GRID; gz++) {
                    assertNotEquals(a.noise(gx, gy, gz), b.noise(gx, gy, gz), "distinct seeds differ at " + gx + "," + gy + "," + gz);
                }
            }
        }
    }

    @Test
    public void testCanonicalSingletonTracksTheManualReseed() {
        long seed = 42;
        PerlinNoiseGenerator reseeded = new PerlinNoiseGenerator(seed);
        PerlinNoiseGenerator.getInstance().reseed(seed);
        for (int gx = 0; gx < 15; gx++) {
            for (int gy = 0; gy < 15; gy++) {
                assertEquals(reseeded.noise(gx, gy, gx + gy * 0.5), PerlinNoiseGenerator.getInstance().noise(gx, gy, gx + gy * 0.5), 1e-12, "singleton mirrors the seeded generator");
            }
        }
    }

    @Test
    public void testReseedingRoundTripRecreatesTheFormerField() {
        final int count = 8;
        // reseed(S) remounts the shared singleton, so the 3-D field is set solely by the seed.
        PerlinNoiseGenerator.getInstance().reseed(0L);
        PerlinNoiseGenerator cur = PerlinNoiseGenerator.getInstance();
        double[] baseline = new double[count];
        for (int i = 0; i < count; i++) { baseline[i] = cur.noise(1.5, 2.5, 3.5 + i * 0.5); }

        PerlinNoiseGenerator.getInstance().reseed(3L);
        cur = PerlinNoiseGenerator.getInstance();
        double[] shifted = new double[count];
        for (int i = 0; i < count; i++) { shifted[i] = cur.noise(1.5, 2.5, 3.5 + i * 0.5); }

        PerlinNoiseGenerator.getInstance().reseed(0L);
        cur = PerlinNoiseGenerator.getInstance();
        double[] restored = new double[count];
        for (int i = 0; i < count; i++) { restored[i] = cur.noise(1.5, 2.5, 3.5 + i * 0.5); }

        boolean differs = false;
        for (int i = 0; i < count; i++) { if (baseline[i] != shifted[i]) { differs = true; break; } }
        assertTrue(differs, "stepping the seed to 3 alters the sampled 3-D field");

        for (int i = 0; i < count; i++) {
            assertEquals(baseline[i], restored[i], 1e-12, "restoring seed 0 reproduces sample " + i);
        }
    }
}


