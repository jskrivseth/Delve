package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockFinderTest {
    @Test
    void raycastSelectsWaterBeforeTheLakeBed() {
        BlockFinder.RayHit hit = BlockFinder.raycast(
                0.5, 4.5, 0.5, 1, 0, 0, 4,
                (x, y, z) -> x == 1 ? Block.WATER : (x == 2 ? Block.STONE : Block.AIR));

        assertEquals(1, hit.x);
        assertEquals(0, hit.placeX);
    }
}
