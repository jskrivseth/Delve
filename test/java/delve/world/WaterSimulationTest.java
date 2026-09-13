package delve.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void sourceFlowsDownAndSidewaysWithAttenuation() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 8, 4, 8);
        World.enqueueWaterUpdate(4, 8, 4);

        World.processWaterUpdates(200);

        assertEquals(7, chunk.waterLevel(4, 7, 4));
        assertEquals(7, chunk.waterLevel(3, 8, 4));
        assertEquals(8, chunk.waterLevel(4, 8, 4));
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
        left.setWaterLevel(WorldChunk.sizeX - 1, 4, 2, 8);
        World.enqueueWaterUpdate(WorldChunk.sizeX - 1, 4, 2);

        World.processWaterUpdates(200);

        assertEquals(7, right.waterLevel(0, 4, 2));
    }

    @Test
    void identicalQueueProducesIdenticalLevels() {
        WorldChunk first = chunk(2, 0);
        first.setWaterLevel(4, 8, 4, 8);
        World.enqueueWaterUpdate(2 * WorldChunk.sizeX + 4, 8, 4);
        World.processWaterUpdates(40);
        int expected = first.waterLevel(3, 8, 4);

        World.unregisterChunk(first);
        chunks.remove(first);
        WorldChunk second = chunk(2, 0);
        second.setWaterLevel(4, 8, 4, 8);
        World.clearWaterUpdates();
        World.enqueueWaterUpdate(2 * WorldChunk.sizeX + 4, 8, 4);
        World.processWaterUpdates(40);

        assertEquals(expected, second.waterLevel(3, 8, 4));
    }
}
