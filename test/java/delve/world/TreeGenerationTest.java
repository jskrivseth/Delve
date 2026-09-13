package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TreeGenerationTest {
    @Test
    void archetypeSelectionIsDeterministicAndVaried() {
        int first = WorldChunk.selectTreeArchetype(120, 64, -45);
        assertEquals(first, WorldChunk.selectTreeArchetype(120, 64, -45));

        boolean[] seen = new boolean[WorldChunk.TreeArchetype.values().length];
        for (int x = -256; x < 256; x++) {
            seen[WorldChunk.selectTreeArchetype(x, 64, 31)] = true;
        }
        int count = 0;
        for (boolean value : seen) {
            if (value) {
                count++;
            }
        }
        assertEquals(seen.length, count);
    }

    @Test
    void treeShapesStayWithinChunkAndDoNotReplaceProtectedTerrain() {
        int[][][] data = new int[WorldChunk.sizeX][WorldChunk.sizeY][WorldChunk.sizeZ];
        data[8][68][8] = Block.STONE;
        data[7][68][8] = Block.WATER;
        int max = WorldChunk.placeTree(data, 8, 64, 8, 5, 2, 3);

        assertEquals(Block.STONE, data[8][68][8]);
        assertEquals(Block.WATER, data[7][68][8]);
        for (int x = 0; x < WorldChunk.sizeX; x++) {
            for (int y = 0; y < WorldChunk.sizeY; y++) {
                for (int z = 0; z < WorldChunk.sizeZ; z++) {
                    int block = data[x][y][z];
                    assertNotEquals(Block.BEDROCK, block);
                    if (block == Block.WOOD || block == Block.LEAVES) {
                        assertEquals(true, x >= 0 && x < WorldChunk.sizeX
                                && y >= 0 && y < WorldChunk.sizeY
                                && z >= 0 && z < WorldChunk.sizeZ);
                    }
                }
            }
        }
        assertEquals(true, max > 64 && max <= WorldChunk.sizeY);
    }

    @Test
    void edgeCandidatesCannotSpillAcrossChunkBoundary() {
        int[][][] data = new int[WorldChunk.sizeX][WorldChunk.sizeY][WorldChunk.sizeZ];
        WorldChunk.placeTree(data, 2, 60, 2, 7, 2, 1);
        for (int y = 0; y < WorldChunk.sizeY; y++) {
            for (int z = 0; z < WorldChunk.sizeZ; z++) {
                if (data[0][y][z] == Block.WOOD || data[0][y][z] == Block.LEAVES
                        || data[WorldChunk.sizeX - 1][y][z] == Block.WOOD
                        || data[WorldChunk.sizeX - 1][y][z] == Block.LEAVES) {
                    throw new AssertionError("Tree footprint reached the protected chunk border");
                }
            }
        }
    }
}
