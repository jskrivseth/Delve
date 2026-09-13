package delve.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterSimulationTest {
    private final List<WorldChunk> chunks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        chunks.forEach(World::unregisterChunk);
        World.clearWaterUpdates();
    }

    private WorldChunk chunk(int x, int z) {
        WorldChunk chunk = new WorldChunk(x, z);
        chunk.isGenerated = true;
        World.registerChunk(chunk);
        chunks.add(chunk);
        return chunk;
    }

    @Test
    void sourceFlowsDownAndSpreadsSidewaysWhenBlocked() {
        WorldChunk chunk = chunk(0, 0);
        chunk.blocks[WorldChunk.blockIndex(4, 7, 4)] = (byte) Block.STONE;
        chunk.setWaterLevel(4, 8, 4, 8);
        World.enqueueWaterUpdate(4, 8, 4);

        World.processWaterUpdates(200);

        assertEquals(0, chunk.waterLevel(4, 7, 4));
        assertEquals(7, chunk.waterLevel(3, 8, 4));
        assertEquals(8, chunk.waterLevel(4, 8, 4));
    }

    @Test
    void verticalColumnDoesNotSpreadUntilItsBottomHitsTerrain() {
        WorldChunk chunk = chunk(0, 0);
        chunk.blocks[WorldChunk.blockIndex(4, 4, 4)] = (byte) Block.STONE;
        chunk.setWaterLevel(4, 8, 4, 8);
        World.enqueueWaterUpdate(4, 8, 4);

        World.processWaterUpdates(200);

        for (int y = 6; y <= 8; y++) {
            assertEquals(0, chunk.waterLevel(3, y, 4));
            assertEquals(0, chunk.waterLevel(5, y, 4));
        }
        assertEquals(6, chunk.waterLevel(3, 5, 4));
        assertEquals(6, chunk.waterLevel(5, 5, 4));
    }

    @Test
    void sourceIsNotReplacedByWeakerFlow() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(1, 4, 1, 8);
        chunk.setWaterLevel(2, 4, 1, 2);
        World.enqueueWaterUpdate(1, 4, 1);
        World.processWaterUpdates(20);
        assertEquals(8, chunk.waterLevel(1, 4, 1));
    }

    @Test
    void flowingWaterTramplesVegetation() {
        WorldChunk chunk = chunk(0, 0);
        chunk.blocks[WorldChunk.blockIndex(4, 7, 4)] = (byte) Block.TALL_GRASS;
        chunk.setWaterLevel(4, 8, 4, 8);
        World.enqueueWaterUpdate(4, 8, 4);

        World.processWaterUpdates(200);

        assertEquals(Block.WATER, chunk.getBlock(4, 7, 4));
        assertEquals(7, chunk.waterLevel(4, 7, 4));
    }

    @Test
    void unsupportedFlowDrainsAfterSourceRemoval() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 8, 4, 8);
        chunk.setWaterLevel(4, 7, 4, 7);
        chunk.setWaterLevel(3, 7, 4, 6);
        chunk.setWaterLevel(4, 8, 4, 0);
        World.enqueueWaterUpdate(4, 8, 4);
        World.enqueueWaterUpdate(4, 7, 4);
        World.enqueueWaterUpdate(3, 7, 4);

        World.processWaterUpdates(500);

        assertEquals(0, chunk.waterLevel(4, 7, 4));
        assertEquals(0, chunk.waterLevel(3, 7, 4));
    }

    @Test
    void propagationCrossesChunkBoundaryDeterministically() {
        WorldChunk left = chunk(0, 0);
        WorldChunk right = chunk(1, 0);
        left.blocks[WorldChunk.blockIndex(WorldChunk.sizeX - 1, 3, 2)] = (byte) Block.STONE;
        left.setWaterLevel(WorldChunk.sizeX - 1, 4, 2, 8);
        World.enqueueWaterUpdate(WorldChunk.sizeX - 1, 4, 2);

        World.processWaterUpdates(200);

        assertEquals(7, right.waterLevel(0, 4, 2));
    }

    @Test
    void generatedWaterSlopesTowardTheShore() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 1);
        chunk.setWaterLevel(5, 6, 4, 1);

        float sharedEdge = chunk.waterCornerHeight(5, 6, 4);
        float shoreline = chunk.waterCornerHeight(4, 6, 4);

        assertTrue(sharedEdge < 1.0f);
        assertTrue(sharedEdge > shoreline);
    }

    @Test
    void sourceWaterKeepsSharedCornerAtFullHeight() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 1);
        chunk.setWaterLevel(5, 6, 4, 8);

        assertEquals(1.0f, chunk.waterCornerHeight(5, 6, 4));
    }

    @Test
    void flowingWaterLevelControlsSurfaceHeight() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 2);
        assertEquals(0.45f, chunk.waterCornerHeight(5, 6, 5), 0.0001f);

        chunk.setWaterLevel(5, 6, 4, 7);
        assertEquals(0.95f, chunk.waterCornerHeight(5, 6, 5), 0.0001f);
    }

    /**
     * Lakes are held by the bowl they fill, not by a source at the top. Once the
     * simulation looks at them they must settle rather than slowly evaporate.
     */
    @Test
    void waterRestingInTheBasinItFillsDoesNotEvaporate() {
        WorldChunk lake = chunk(0, 0);
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                lake.blocks[WorldChunk.blockIndex(x, 5, z)] = (byte) Block.STONE;
                lake.setWaterLevel(x, 6, z, 1);
            }
        }
        for (int x = 2; x <= 6; x++) {
            World.enqueueWaterUpdate(x, 6, 3);
        }

        World.processWaterUpdates(400);

        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                assertEquals(1, lake.waterLevel(x, 6, z),
                        "lake water at (" + x + ",6," + z + ") evaporated");
            }
        }
    }

    /**
     * Water the player started is different: with its supply broken it has no
     * terrain holding it, so everything it fed has to drain away.
     */
    @Test
    void breakingAPlacedSourceReclaimsTheWaterItFed() {
        WorldChunk hall = chunk(0, 0);
        hall.blocks[WorldChunk.blockIndex(8, 6, 8)] = (byte) Block.STONE;
        hall.setWaterLevel(8, 7, 8, 8);
        World.enqueueWaterUpdate(8, 7, 8);
        World.processWaterUpdates(400);
        assertTrue(hall.waterLevel(7, 7, 8) >= 2, "the source should have fed the floor");

        hall.setWaterLevel(8, 7, 8, 0);
        World.enqueueWaterUpdate(8, 7, 8);
        World.enqueueWaterDrainNeighborhood(8, 7, 8);
        World.processWaterUpdates(800);

        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                assertEquals(0, hall.waterLevel(x, 7, z),
                        "water at (" + x + ",7," + z + ") outlived its source");
            }
        }
    }

    @Test
    void identicalQueueProducesIdenticalLevels() {
        WorldChunk first = chunk(2, 0);
        first.blocks[WorldChunk.blockIndex(4, 7, 4)] = (byte) Block.STONE;
        first.setWaterLevel(4, 8, 4, 8);
        World.enqueueWaterUpdate(2 * WorldChunk.sizeX + 4, 8, 4);
        World.processWaterUpdates(40);
        int expected = first.waterLevel(3, 8, 4);

        World.unregisterChunk(first);
        chunks.remove(first);
        WorldChunk second = chunk(2, 0);
        second.blocks[WorldChunk.blockIndex(4, 7, 4)] = (byte) Block.STONE;
        second.setWaterLevel(4, 8, 4, 8);
        World.clearWaterUpdates();
        World.enqueueWaterUpdate(2 * WorldChunk.sizeX + 4, 8, 4);
        World.processWaterUpdates(40);

        assertEquals(expected, second.waterLevel(3, 8, 4));
    }
}
