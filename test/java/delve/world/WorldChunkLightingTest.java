package delve.world;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkLightingTest {
    private final List<WorldChunk> chunks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        chunks.forEach(World::unregisterChunk);
    }

    private WorldChunk rockChunk(int x) {
        WorldChunk chunk = new WorldChunk(x, 2000);
        Arrays.fill(chunk.blocks, (byte) Block.STONE);
        chunk.isGenerated = true;
        World.registerChunk(chunk);
        chunks.add(chunk);
        return chunk;
    }

    private static void light(WorldChunk chunk) throws Exception {
        Method compute = WorldChunk.class.getDeclaredMethod("computeSkyLight");
        compute.setAccessible(true);
        compute.invoke(chunk);
    }

    private static void air(WorldChunk chunk, int x, int y) {
        chunk.blocks[WorldChunk.blockIndex(x, y, 8)] = (byte) Block.AIR;
    }

    @Test
    void missingAndUnlitNeighborsDoNotInjectDaylightIntoSealedCaves() throws Exception {
        WorldChunk left = rockChunk(2000);
        air(left, 15, 20);
        light(left);
        assertEquals(0, left.localLight(15, 20, 8), "Missing terrain is not a sky source");

        WorldChunk right = rockChunk(2001);
        air(right, 0, 20);
        light(left);
        assertEquals(0, left.localLight(15, 20, 8), "An unlit neighbor is not a sky source");
        light(right);
        assertEquals(0, right.localLight(0, 20, 8));
    }

    @Test
    void startupLightOnlyGrowsThenSettlesAndClosingAnEntranceRemovesIt() throws Exception {
        WorldChunk left = rockChunk(2000);
        WorldChunk right = rockChunk(2001);
        air(left, 15, 20);
        air(right, 0, 20);
        for (int y = 20; y < WorldChunk.sizeY; y++) {
            air(right, 1, y);
        }
        light(left);
        assertEquals(0, left.localLight(15, 20, 8));

        int previous = 0;
        for (int pass = 0; pass < 8; pass++) {
            light(right);
            light(left);
            int current = left.localLight(15, 20, 8);
            assertTrue(current >= previous, "Static startup lighting must not oscillate");
            previous = current;
        }
        assertEquals(13, previous, "Real sky should cross the seam with attenuation");
        for (int pass = 0; pass < 4; pass++) {
            light(left);
            light(right);
            assertEquals(13, left.localLight(15, 20, 8));
        }

        right.blocks[WorldChunk.blockIndex(1, 21, 8)] = (byte) Block.STONE;
        for (int pass = 0; pass < WorldChunk.MAX_LIGHT; pass++) {
            light(right);
            light(left);
        }
        assertEquals(0, left.localLight(15, 20, 8), "Closing a shaft must remove stale light");
        assertEquals(0, right.localLight(0, 20, 8));
    }
}
