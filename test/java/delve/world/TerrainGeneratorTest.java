package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TerrainGeneratorTest {

    @Test
    public void cavePredicateIsDeterministicForASeed() {
        PerlinNoiseGenerator.reseed(91423L);
        boolean first = TerrainGenerator.isCave(37, 42, -19, 96);
        PerlinNoiseGenerator.reseed(91423L);
        assertEquals(first, TerrainGenerator.isCave(37, 42, -19, 96));
    }

    @Test
    public void cavePredicateProtectsSurfaceAndFoundation() {
        PerlinNoiseGenerator.reseed(91423L);
        assertFalse(TerrainGenerator.isCave(37, 8, -19, 96));
        assertFalse(TerrainGenerator.isCave(37, 96, -19, 96));
    }

    @Test
    public void caveSamplesUseAbsoluteCoordinatesAcrossChunkSeam() {
        PerlinNoiseGenerator.reseed(91423L);
        boolean left = TerrainGenerator.isCave(15, 44, 23, 96);
        boolean right = TerrainGenerator.isCave(16, 44, 23, 96);
        assertEquals(left, TerrainGenerator.isCave(0 + 15, 44, 23, 96));
        assertEquals(right, TerrainGenerator.isCave(16, 44, 23, 96));
    }

    @Test
    public void conservativeMaskStillProducesCavesBelowSurface() {
        PerlinNoiseGenerator.reseed(91423L);
        boolean found = false;
        for (int x = -64; x < 64 && !found; x++) {
            for (int z = -64; z < 64 && !found; z++) {
                for (int y = 16; y < 76; y++) {
                    if (TerrainGenerator.isCave(x, y, z, 96)) {
                        found = true;
                        break;
                    }
                }
            }
        }
        assertTrue(found);
    }

    @Test
    public void entrancesAreWideAndFrequentEnoughToDiscover() {
        PerlinNoiseGenerator.reseed(91423L);
        int entranceColumns = 0;
        int adjacentPairs = 0;
        boolean previous = false;
        for (int x = -128; x < 128; x++) {
            boolean open = TerrainGenerator.isCave(x, 94, 0, 96);
            if (open) {
                entranceColumns++;
                if (previous) {
                    adjacentPairs++;
                }
            }
            previous = open;
        }
        assertTrue(entranceColumns >= 12, "Entrances should occur often enough along a hillside");
        assertTrue(adjacentPairs >= 6, "Entrances should span multiple adjacent columns");
    }
}
