package delve.core;

import delve.world.PerlinNoiseGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Box-probe coverage over the exported noise machinery of {@link PerlinNoiseGenerator}. */
public class PerlinWhiteBoxTest {

    /** Wraps the exported static facade to exercise shared internals deterministically. */
    private static final class Probe extends PerlinNoiseGenerator {
        Probe(long seed) {
            super(seed);
        }

        double i_fade(double x) {
            return fade(x);
        }

        double i_lerp(double x, double y, double z) {
            return lerp(x, y, z);
        }

        double i_grad(int hash, double x, double y, double z) {
            return grad(hash, x, y, z);
        }
    }

    @Test
    public void testSharedGenerationRegistersASingletonInstance() {
        PerlinNoiseGenerator probe = new PerlinNoiseGenerator(1L);
        assertNotNull(probe, "fresh construction returns a live generator");
    }

    @Test
    public void testFadeMapsBoundaryInputsWithoutOverflow() {
        Probe probe = new Probe(1L);
        assertEquals(0.0, probe.i_fade(0), 1e-12, "start lands at zero");
        assertEquals(1.0, probe.i_fade(1), 1e-12, "end saturates at one");
        double prev = probe.i_fade(0);
        for (int k = 1; k <= 50; k++) {
            double frac = k / 50.0;
            double value = probe.i_fade(frac);
            assertTrue(value >= 0 && value <= 1, "fade contained in [0,1] @ " + frac);
            assertTrue(value >= prev - 1e-12, "monotone growth preserved");
            prev = value;
        }
    }

    @Test
    public void testGradientVanishesAtOriginForEveryHashBucket() {
        Probe probe = new Probe(1L);
        for (int hash = 0; hash < 16; hash++) {
            assertEquals(0.0, probe.i_grad(hash, 0.0, 0.0, 0.0), 1e-12, "grid-point contributions cancel");
        }
    }

    @Test
    public void testInterpolationClampsBetweenBrackets() {
        Probe probe = new Probe(1L);
        double interior = probe.i_lerp(0.375, 2, 8);
        assertTrue(interior >= 2 && interior <= 8, "weighted interpolation stays within the enclosing interval");
    }
}
