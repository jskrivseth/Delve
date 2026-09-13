package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BiomeSurfaceVariationTest {

    @Test
    void surfacePatchesAreDeterministicForAbsoluteCoordinates() {
        PerlinNoiseGenerator.reseed(91423L);
        int first = WorldChunk.earthSurfacePatch(
                Block.GRASS, EarthBiome.TEMPERATE_FOREST, 0.62f, 0.20f, 173, -241);
        PerlinNoiseGenerator.reseed(91423L);
        int second = WorldChunk.earthSurfacePatch(
                Block.GRASS, EarthBiome.TEMPERATE_FOREST, 0.62f, 0.20f, 173, -241);

        assertEquals(first, second);
    }

    @Test
    void surfacePatchSamplesRemainStableAtChunkCoordinates() {
        PerlinNoiseGenerator.reseed(91423L);
        int worldX = -WorldChunk.sizeX + 15;
        int worldZ = WorldChunk.sizeZ * 4;
        int expected = WorldChunk.earthSurfacePatch(
                Block.GRASS, EarthBiome.TEMPERATE_GRASSLAND, 0.48f, 0.10f, worldX, worldZ);

        // Both chunks use the same absolute coordinate at their shared lattice.
        int fromAdjacentChunk = WorldChunk.earthSurfacePatch(
                Block.GRASS, EarthBiome.TEMPERATE_GRASSLAND, 0.48f, 0.10f, -1, worldZ);

        assertEquals(expected, fromAdjacentChunk);
    }

    @Test
    void biomePatchRulesProduceVisibleDifferences() {
        PerlinNoiseGenerator.reseed(91423L);
        boolean foundDesertRock = false;
        boolean foundForestPeat = false;
        for (int x = -256; x < 256 && (!foundDesertRock || !foundForestPeat); x++) {
            for (int z = -256; z < 256 && (!foundDesertRock || !foundForestPeat); z++) {
                int desert = WorldChunk.earthSurfacePatch(
                        Block.SAND, EarthBiome.HOT_DESERT, 0.55f, 0.05f, x, z);
                int forest = WorldChunk.earthSurfacePatch(
                        Block.GRASS, EarthBiome.TEMPERATE_FOREST, 0.55f, 0.20f, x, z);
                foundDesertRock |= desert == Block.SANDSTONE;
                foundForestPeat |= forest == Block.PEAT;
            }
        }

        assertNotEquals(false, foundDesertRock);
        assertNotEquals(false, foundForestPeat);
    }

    @Test
    void dryGrassStaysNeutralAgainstWarmGround() {
        assertEquals(Block.TINT_NONE, Block.biomeTintKind(Block.BROWN_GRASS));
    }
}
