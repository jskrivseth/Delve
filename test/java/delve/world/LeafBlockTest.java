package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeafBlockTest {
    private static final int[] VARIANTS = {
        Block.LEAVES, Block.DARK_LEAVES, Block.GOLDEN_LEAVES, Block.PALE_LEAVES
    };

    @Test
    void everyLeafVariantUsesTransparentMeshingAndLightRules() {
        for (int type : VARIANTS) {
            assertTrue(Block.isLeaf(type));
            assertTrue(Block.isTransparent(type));
            assertTrue(Block.transmitsLight(type));
            assertEquals(2, Block.lightCost(type));
            assertTrue(Block.isCollidable(type));
            assertEquals(Block.sideTileCol(Block.LEAVES), Block.sideTileCol(type));
            assertEquals(Block.sideTileRow(Block.LEAVES), Block.sideTileRow(type));
        }
    }

    @Test
    void foliageSelectionIsDeterministicAndUsesLeafVariants() {
        int first = WorldChunk.selectTreeFoliage(17, 64, -23, 1);
        assertEquals(first, WorldChunk.selectTreeFoliage(17, 64, -23, 1));
        boolean sawVariant = false;
        for (int x = -128; x < 128; x++) {
            int foliage = WorldChunk.selectTreeFoliage(x, 64, 11, 1);
            assertTrue(Block.isLeaf(foliage));
            sawVariant |= foliage != Block.LEAVES;
        }
        assertTrue(sawVariant);
    }
}
