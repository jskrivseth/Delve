package delve.world;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Pins the flat-storage layout and the indexed mesh topology. */
public class WorldChunkTest {

    /** Empty neighbourhood: nothing occludes, everything is fully lit. */
    private static final Block.SolidityLookup OPEN_SKY = new Block.SolidityLookup() {
        @Override
        public boolean isSolid(int x, int y, int z) {
            return false;
        }

        @Override
        public int lightAt(int x, int y, int z) {
            return WorldChunk.MAX_LIGHT;
        }
    };

    @Test
    public void testBlockIndexWalksZFastestThenYThenX() {
        // Enumerate independently of blockIndex in the layout the docs promise:
        // x outer, y middle, z innermost -- the direction the mesher loops.
        int expected = 0;
        for (int x = 0; x < WorldChunk.sizeX; x++) {
            for (int y = 0; y < WorldChunk.sizeY; y++) {
                for (int z = 0; z < WorldChunk.sizeZ; z++) {
                    assertEquals(expected++, WorldChunk.blockIndex(x, y, z),
                            "index @" + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(WorldChunk.sizeX * WorldChunk.sizeY * WorldChunk.sizeZ, expected);
    }

    @Test
    public void testBlockStorageIsOneByteArrayPerChunk() {
        WorldChunk chunk = new WorldChunk(0, 0);
        assertEquals(WorldChunk.sizeX * WorldChunk.sizeY * WorldChunk.sizeZ, chunk.blocks.length);
    }

    @Test
    public void testStoredByteRoundTripsThroughGetBlock() {
        WorldChunk chunk = new WorldChunk(0, 0);
        int x = 7, y = 64, z = 11;
        chunk.blocks[WorldChunk.blockIndex(x, y, z)] = (byte) Block.GRANITE;
        assertEquals(Block.GRANITE, chunk.getBlock(x, y, z));
        // The flat store is signed; the read must mask the sign bit so ids
        // survive unchanged even if the palette ever grows past 127.
        chunk.blocks[WorldChunk.blockIndex(x, y, z)] = (byte) 255;
        assertEquals(255, chunk.getBlock(x, y, z));
    }

    @Test
    public void testGeneratedGraniteUsesAnOpaqueTerrainTile() {
        assertEquals(Block.sideTileCol(Block.STONE), Block.sideTileCol(Block.GRANITE));
        assertEquals(Block.sideTileRow(Block.STONE), Block.sideTileRow(Block.GRANITE));
        assertFalse(Block.isTransparent(Block.GRANITE));
    }

    @Test
    public void testWriteCubeEmitsFourVerticesAndSixIndicesPerFace() {
        boolean[] allFaces = {true, true, true, true, true, true};
        FloatBuffer verts = org.lwjgl.BufferUtils.createFloatBuffer(6 * 6 * Block.FLOATS_PER_VERTEX);
        IntBuffer inds = org.lwjgl.BufferUtils.createIntBuffer(6 * Block.INDICES_PER_FACE);

        Block.writeCube(verts, inds, 3, 40, 5, allFaces, Block.STONE, OPEN_SKY);

        // Half the vertices the pre-index writer spent, same triangle soup.
        assertEquals(6 * 4 * Block.FLOATS_PER_VERTEX, verts.position());
        assertEquals(6 * Block.INDICES_PER_FACE, inds.position());

        inds.rewind();
        int[] indexList = new int[inds.remaining()];
        inds.get(indexList);
        for (int face = 0; face < 6; face++) {
            int base = face * 4;
            assertArrayEquals(
                    new int[]{base, base + 1, base + 2, base + 2, base + 3, base},
                    java.util.Arrays.copyOfRange(indexList, face * 6, face * 6 + 6),
                    "triangles 0-1-2 and 2-3-0 over the shared corners, face " + face);
        }
    }

    /**
     * Crossed sprite planes each contribute one single-winded quad; the
     * reversed duplicate was removed because vegetation chunks render with
     * culling off, and the coincident copy only double-blended (visible as
     * z-fighting where blades overlapped).
     */
    @Test
    public void testWriteCrossSpriteSharesCornersAcrossBothDiagonals() {
        FloatBuffer verts = org.lwjgl.BufferUtils.createFloatBuffer(4 * 6 * Block.FLOATS_PER_VERTEX);
        IntBuffer inds = org.lwjgl.BufferUtils.createIntBuffer(4 * Block.INDICES_PER_FACE);

        Block.writeCrossSprite(verts, inds, 1, 33, 2, Block.TALL_GRASS, OPEN_SKY);

        assertEquals(2 * 4 * Block.FLOATS_PER_VERTEX, verts.position());
        assertEquals(2 * Block.INDICES_PER_FACE, inds.position());
    }
}
