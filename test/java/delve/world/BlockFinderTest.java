package delve.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockFinderTest {
    private WorldChunk chunk;

    @AfterEach
    void tearDown() {
        if (chunk != null) {
            World.unregisterChunk(chunk);
        }
        World.clearWaterUpdates();
    }

    @Test
    void raycastSelectsWaterBeforeTheLakeBed() {
        BlockFinder.RayHit hit = BlockFinder.raycast(
                0.5, 4.5, 0.5, 1, 0, 0, 4,
                (x, y, z) -> x == 1 ? Block.WATER : (x == 2 ? Block.STONE : Block.AIR));

        assertEquals(1, hit.x);
        assertEquals(0, hit.placeX);
    }

    @Test
    void manualWaterPlacementAndBreakUpdateBlockAndFluidState() {
        chunk = new WorldChunk(0, 0);
        chunk.isGenerated = true;
        World.registerChunk(chunk);

        BlockFinder.setBlockType(chunk, 4, 8, 4, Block.WATER);
        assertEquals(Block.WATER, chunk.getBlock(4, 8, 4));
        assertEquals(8, chunk.waterLevel(4, 8, 4));

        BlockFinder.setBlockType(chunk, 4, 8, 4, Block.AIR);
        assertEquals(Block.AIR, chunk.getBlock(4, 8, 4));
        assertEquals(0, chunk.waterLevel(4, 8, 4));
    }
}
