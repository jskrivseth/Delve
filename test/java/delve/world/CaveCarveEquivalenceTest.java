package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hot cave predicate grew lossless early outs (skip remaining noise
 * samples once the outcome cannot change). A single mismatch would reshape
 * every cave in every world, so this pins the optimized path against a
 * deliberately naive transcription of the ORIGINAL full-evaluation formulas,
 * voxel by voxel, across a deterministic coordinate corpus and surface
 * profiles that exercise the entrance band, the surface buffer, and the
 * deep-volume region.
 */
class CaveCarveEquivalenceTest {

    private static final double SX = 46.0;
    private static final double SY = 30.0;
    private static final double DETAIL = 15.0;
    private static final int ENTRANCE_DEPTH = 11;
    private static final int SURFACE_BUFFER = 4;
    private static final int MIN_Y = 8;

    /** Original, no shortcuts: evaluate every sample, decide once. */
    private static boolean tunnelOriginal(int x, int y, int z, double radius) {
        double warp = PerlinNoiseGenerator.getNoise((x + 719) / 104.0, (y - 283) / 76.0, (z + 467) / 104.0) * 9.0;
        double first = Math.abs(PerlinNoiseGenerator.getNoise((x + warp) / SX, (y - warp * 0.35) / SY, (z - warp) / SX));
        double second = Math.abs(PerlinNoiseGenerator.getNoise((z + 353 - warp) / (SX * 1.12),
                (x - 127 + warp) / (SX * 1.12), (y + 211 + warp * 0.35) / (SY * 1.12)));
        double detail = Math.abs(PerlinNoiseGenerator.getNoise((x + 173) / DETAIL, (y - 67) / DETAIL, (z - 251) / DETAIL));
        return Math.max(first, second) + detail * 0.08 < radius;
    }

    private static boolean erodedOriginal(int x, int y, int z, int surfaceY) {
        int depth = surfaceY - y;
        double region = PerlinNoiseGenerator.getNoise((x - 811) / 150.0, (z + 613) / 150.0);
        if (region < 0.34) {
            return false;
        }
        int anchorY = surfaceY - ENTRANCE_DEPTH;
        if (!tunnelOriginal(x, anchorY, z, 0.16) && !tunnelOriginal(x, anchorY - 4, z, 0.16)) {
            return false;
        }
        double broad = Math.abs(PerlinNoiseGenerator.getNoise((x + 401) / 62.0, (surfaceY - 19) / 42.0, (z - 337) / 62.0));
        double edge = Math.abs(PerlinNoiseGenerator.getNoise((x - 149) / 21.0, (z + 263) / 21.0));
        double aperture = 0.105 + depth * 0.019;
        return broad * 0.84 + edge * 0.16 < aperture;
    }

    private static boolean caveOriginal(int x, int y, int z, int surfaceY) {
        if (y <= MIN_Y || y > surfaceY) {
            return false;
        }
        int depthBelowSurface = surfaceY - y;
        if (depthBelowSurface <= ENTRANCE_DEPTH && erodedOriginal(x, y, z, surfaceY)) {
            return true;
        }
        if (depthBelowSurface <= SURFACE_BUFFER) {
            return false;
        }
        double depth = Math.min(1.0, Math.max(0.0, (depthBelowSurface - SURFACE_BUFFER) / 72.0));
        double radius = 0.105 + depth * 0.020;
        return tunnelOriginal(x, y, z, radius);
    }

    @Test
    void earlyOutsLeaveTheCaveFieldByteIdentical() {
        int mismatches = 0;
        int checks = 0;
        int[] surfaces = {12, 20, 30, 44, 64, 90};
        // Deterministic pseudo-random walk over a wide coordinate envelope.
        long s = 0x5DEECE66DL;
        for (int i = 0; i < 4200; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            int x = (int) Math.floorMod(s, 60_001) - 30_000;
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            int z = (int) Math.floorMod(s, 60_001) - 30_000;
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            int surfaceY = surfaces[(int) Math.floorMod(s, surfaces.length)];
            for (int dy = 0; dy < 5; dy++) {
                int y = Math.max(6, surfaceY - 2 - (int) Math.floorMod(s >>> 3, 96)) + dy;
                checks++;
                if (TerrainGenerator.isCave(x, y, z, surfaceY) != caveOriginal(x, y, z, surfaceY)) {
                    mismatches++;
                }
            }
        }
        assertEquals(0, mismatches,
                mismatches + "/" + checks + " voxels disagree with the original full-evaluation formula");
    }
}
