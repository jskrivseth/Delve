package delve.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterSimulationTest {
    private final List<WorldChunk> chunks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        chunks.forEach(chunk -> {
            World.unregisterChunk(chunk);
            World.chunks.remove(chunk);
        });
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
    void globalPassScansUnqueuedWaterDownThenOutAcrossTerrain() {
        WorldChunk chunk = chunk(0, 0);
        World.chunks.add(chunk);
        chunk.blocks[WorldChunk.blockIndex(2, 4, 4)] = (byte) Block.STONE;
        chunk.blocks[WorldChunk.blockIndex(3, 4, 4)] = (byte) Block.STONE;
        chunk.blocks[WorldChunk.blockIndex(4, 4, 4)] = (byte) Block.STONE;
        chunk.blocks[WorldChunk.blockIndex(5, 4, 4)] = (byte) Block.STONE;
        chunk.setWaterLevel(4, 8, 4, 8);

        int evaluated = World.processGlobalWaterUpdates();

        assertTrue(evaluated > 0, "active chunk water was not evaluated");
        assertEquals(7, chunk.waterLevel(4, 7, 4));
        assertEquals(7, chunk.waterLevel(4, 5, 4));
        assertEquals(6, chunk.waterLevel(3, 5, 4));

        World.processGlobalWaterUpdates();

        assertEquals(6, chunk.waterLevel(3, 5, 4));
        assertEquals(5, chunk.waterLevel(2, 5, 4));
    }

    @Test
    void globalPassResumesAtItsCursorWithinTheSnapshot() {
        WorldChunk chunk = chunk(0, 0);
        World.chunks.add(chunk);
        chunk.setWaterLevel(4, 8, 4, 8);
        chunk.setWaterLevel(8, 8, 4, 8);

        assertEquals(1, World.processGlobalWaterUpdates(1));
        assertEquals(1, World.processGlobalWaterUpdates(1));
    }

    @Test
    void adjacentWaterNeighborhoodsReachAStableFixedPoint() {
        WorldChunk chunk = chunk(0, 0);
        World.chunks.add(chunk);
        for (int x = 1; x < 15; x++) {
            chunk.blocks[WorldChunk.blockIndex(x, 4, 4)] = (byte) Block.STONE;
        }
        chunk.setWaterLevel(4, 8, 4, 8);
        chunk.setWaterLevel(11, 8, 4, 8);

        for (int i = 0; i < 20; i++) {
            World.processGlobalWaterUpdates(1000);
        }
        byte[] settled = chunk.waterLevels.clone();
        for (int i = 0; i < 20; i++) {
            World.processGlobalWaterUpdates(1000);
        }

        assertArrayEquals(settled, chunk.waterLevels,
                "adjacent water neighborhoods kept changing after settling");
    }

    @Test
    void adjacentNeighborhoodsSettleWithPartialCursorPasses() {
        WorldChunk chunk = chunk(0, 0);
        World.chunks.add(chunk);
        for (int x = 1; x < 15; x++) {
            chunk.blocks[WorldChunk.blockIndex(x, 4, 4)] = (byte) Block.STONE;
        }
        chunk.setWaterLevel(4, 8, 4, 8);
        chunk.setWaterLevel(11, 8, 4, 8);

        for (int i = 0; i < 1000; i++) {
            World.processGlobalWaterUpdates(1);
        }
        byte[] settled = chunk.waterLevels.clone();
        for (int i = 0; i < 1000; i++) {
            World.processGlobalWaterUpdates(1);
        }

        assertArrayEquals(settled, chunk.waterLevels,
                "partial cursor passes kept adjacent neighborhoods mutating");
    }

    @Test
    void placedSourceRunsBeforeTerrainWaterBacklog() {
        WorldChunk chunk = chunk(0, 0);
        chunk.blocks[WorldChunk.blockIndex(4, 7, 4)] = (byte) Block.STONE;
        chunk.setWaterLevel(4, 8, 4, 8);
        for (int i = 0; i < 1000; i++) {
            World.enqueueWaterUpdate(12, 12, 12);
        }

        World.enqueueWaterUpdateImmediate(4, 8, 4);
        World.processWaterUpdates(1);

        assertEquals(7, chunk.waterLevel(3, 8, 4),
                "placed source was starved behind terrain-water settling");
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

    /**
     * Uniform generated shallows meeting their own shoreline must tile flat:
     * every corner of a level field derives the same height, with no meniscus
     * bump climbing the bank. Slope comes only from genuinely unequal levels.
     */
    @Test
    void uniformGeneratedWaterMeetsTheShoreFlat() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 1);
        chunk.setWaterLevel(5, 6, 4, 1);

        float sharedEdge = chunk.waterCornerHeight(5, 6, 4);
        float shoreline = chunk.waterCornerHeight(4, 6, 4);

        assertEquals(WorldChunk.waterSurfaceHeight(1), sharedEdge, 0.0001f);
        assertEquals(sharedEdge, shoreline, 0.0001f);
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

        // Meeting a level-7 neighbour, the shared lattice corner sits midway
        // between the two surfaces -- a slope, not a step to the high side.
        chunk.setWaterLevel(5, 6, 4, 7);
        assertEquals(0.70f, chunk.waterCornerHeight(5, 6, 5), 0.0001f);
    }

    /**
     * Free-standing water keeps its slope: a cascade over successive weaker
     * levels must place each lattice corner strictly between the adjoining
     * column surfaces, monotonically downhill.
     */
    @Test
    void cascadeCornersSlopeMonotonicallyDownstream() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(3, 6, 4, 6);
        chunk.setWaterLevel(4, 6, 4, 4);
        chunk.setWaterLevel(5, 6, 4, 2);

        float up = chunk.waterCornerHeight(4, 6, 4);
        float down = chunk.waterCornerHeight(5, 6, 4);

        float six = WorldChunk.waterSurfaceHeight(6);
        float four = WorldChunk.waterSurfaceHeight(4);
        float two = WorldChunk.waterSurfaceHeight(2);
        assertTrue(up < six && up > four, "upstream corner not between its columns");
        assertTrue(up > down, "cascade corner did not descend");
        assertTrue(down < four && down > two, "downstream corner not between its columns");
    }

    /**
     * Spill-over: a sheet ending where liquid continues one row down must
     * ramp its lip down over the block, not shear off at a vertical drop.
     * The apron is bounded strictly between the carried sheet height and
     * the cell floor.
     */
    @Test
    void sheetEdgesAppronDownOverADrop() {
        WorldChunk chunk = chunk(0, 0);
        // The lip carries a block under it: aprons are lawful on sills,
        // never on water hanging over an undercut void.
        chunk.blocks[WorldChunk.blockIndex(3, 5, 4)] = (byte) Block.STONE;
        chunk.blocks[WorldChunk.blockIndex(4, 5, 4)] = (byte) Block.STONE;
        chunk.setWaterLevel(3, 6, 4, 4);
        chunk.setWaterLevel(4, 6, 4, 4);
        // The pool one row down, directly beyond the lip line at x=5.
        chunk.setWaterLevel(5, 5, 3, 4);
        chunk.setWaterLevel(5, 5, 4, 4);

        float interior = chunk.waterCornerHeight(4, 6, 4);
        float lip = chunk.waterCornerHeight(5, 6, 4);

        assertEquals(WorldChunk.waterSurfaceHeight(4), interior, 0.0001f);
        assertTrue(lip < interior, "lip stayed flush instead of spilling over");
        assertTrue(lip > 0.0f, "apron punched through the cell floor");
    }

    /**
     * Unsupported lip: the sheet's own carrier cell hangs over open space.
     * No apron may issue; the edge holds its flat mean, and the void is
     * dealt with by the sheet below.
     */
    @Test
    void apronNeedsABlockUnderTheCarrier() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 4);
        chunk.setWaterLevel(5, 5, 4, 4);   // water one row down, but the
                                           // carrier at (5?,6) has no sill:
                                           // its own column below is open.

        float corner = chunk.waterCornerHeight(5, 6, 4);
        // Wet column (4,6,4) is unsupported (air beneath) -> apron gated off.
        assertEquals(WorldChunk.waterSurfaceHeight(4), corner, 0.0001f);
    }

    /**
     * Vertical fill: where water directly overhangs a lower sheet, that
     * sheet's shared corner drives flush to the cell top -- the underside
     * of the overhanging water -- welding surface to surface instead of
     * leaving a peeled strip of bank between them. Interior corners of the
     * same sheet stay flat.
     */
    @Test
    void sheetUnderOverhangingWaterFillsVertically() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 5, 3, 4);
        chunk.setWaterLevel(4, 5, 4, 4);
        // Thick water hanging one row directly above the columns x=5.
        chunk.setWaterLevel(5, 6, 3, 8);
        chunk.setWaterLevel(5, 6, 4, 8);

        float sealed = chunk.waterCornerHeight(5, 5, 4);
        float interior = chunk.waterCornerHeight(4, 5, 4);

        assertEquals(1.0f, sealed, 0.0001f);
        assertTrue(interior < 1.0f, "whole sheet flattened against the ceiling");
    }

    /**
     * Ground beneath a dry neighbour is NOT a participant: a shoreline over
     * solid ground must stay flush no matter what the sheet rests on.
     */
    @Test
    void flushShorelineIgnoresGroundBelow() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 4);
        chunk.blocks[WorldChunk.blockIndex(5, 5, 4)] = (byte) Block.STONE;

        float interior = chunk.waterCornerHeight(4, 6, 4);
        float shore = chunk.waterCornerHeight(5, 6, 4);

        assertEquals(WorldChunk.waterSurfaceHeight(4), shore, 0.0001f);
        assertEquals(interior, shore, 0.0001f);
    }

    /**
     * No corner may ever rise above the tallest wet column it touches: that
     * was the bank-climbing bump, where two shallow films stacked into a
     * corner higher than the water feeding them.
     */
    @Test
    void cornerNeverExceedsItsTallestColumn() {
        WorldChunk chunk = chunk(0, 0);
        chunk.setWaterLevel(4, 6, 4, 3);
        chunk.setWaterLevel(4, 6, 5, 3);
        chunk.setWaterLevel(5, 6, 4, 2);

        // Highest participating surface: level 3 = 0.55.
        float corner = chunk.waterCornerHeight(5, 6, 5);
        assertTrue(corner <= WorldChunk.waterSurfaceHeight(3) + 0.0001f);
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
        for (int i = 1; i <= 7; i++) {
            lake.blocks[WorldChunk.blockIndex(1, 6, i)] = (byte) Block.STONE;
            lake.blocks[WorldChunk.blockIndex(7, 6, i)] = (byte) Block.STONE;
            lake.blocks[WorldChunk.blockIndex(i, 6, 1)] = (byte) Block.STONE;
            lake.blocks[WorldChunk.blockIndex(i, 6, 7)] = (byte) Block.STONE;
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

    @Test
    void generatedWaterSpillsOverAnAdjacentCaveLedge() {
        WorldChunk lake = chunk(0, 0);
        lake.blocks[WorldChunk.blockIndex(4, 5, 4)] = (byte) Block.STONE;
        lake.blocks[WorldChunk.blockIndex(5, 3, 4)] = (byte) Block.STONE;
        lake.setWaterLevel(4, 6, 4, 1);

        lake.enqueueGeneratedWaterOutlets();
        World.processWaterUpdates(200);

        assertEquals(1, lake.waterLevel(4, 6, 4),
                "generated lake cell was consumed instead of acting as a source");
        assertTrue(lake.waterLevel(5, 4, 4) > 1,
                "generated lake did not emit flow into the adjacent cave");
    }

    @Test
    void diggingIntoGeneratedLakeFromBelowCreatesAContinuousSource() {
        WorldChunk lake = chunk(0, 0);
        lake.blocks[WorldChunk.blockIndex(4, 5, 4)] = (byte) Block.STONE;
        lake.blocks[WorldChunk.blockIndex(4, 2, 4)] = (byte) Block.STONE;
        lake.setWaterLevel(4, 6, 4, 1);

        // Simulate breaking the lake bed and the neighborhood update issued by
        // BlockFinder after the edit.
        lake.blocks[WorldChunk.blockIndex(4, 5, 4)] = (byte) Block.AIR;
        World.enqueueWaterUpdate(4, 6, 4);
        World.processWaterUpdates(200);

        assertEquals(1, lake.waterLevel(4, 6, 4),
                "the generated lake source disappeared after its bed was dug");
        assertEquals(7, lake.waterLevel(4, 5, 4),
                "the lake did not emit source-strength flow through the breach");
        assertTrue(lake.waterLevel(4, 3, 4) > 1,
                "the emitted flow did not descend to the cave floor");
    }

    /**
     * A tunneller reaching a lake from the side expects it to pour in even
     * though the tunnel floor is solid: the breach is covered overhead, so it
     * is a void water can escape into, not open sky.
     */
    @Test
    void diggingAHorizontalTunnelIntoALakeSpillsIntoIt() {
        WorldChunk hill = chunk(0, 0);
        for (int x = 2; x <= 13; x++) {
            for (int y = 0; y <= 9; y++) {
                for (int z = 2; z <= 6; z++) {
                    hill.blocks[WorldChunk.blockIndex(x, y, z)] = (byte) Block.STONE;
                }
            }
        }
        for (int x = 11; x <= 12; x++) {   // buried lake pocket
            for (int y = 7; y <= 9; y++) {
                for (int z = 2; z <= 6; z++) {
                    hill.blocks[WorldChunk.blockIndex(x, y, z)] = (byte) Block.WATER;
                    hill.setWaterLevel(x, y, z, 1);
                }
            }
        }
        for (int x = 4; x <= 10; x++) {    // bore the tunnel, breaking the wall
            hill.blocks[WorldChunk.blockIndex(x, 7, 4)] = (byte) Block.AIR;
        }
        World.enqueueWaterUpdate(11, 7, 4);
        World.enqueueWaterUpdate(10, 7, 4);
        World.processWaterUpdates(400);

        assertTrue(hill.waterLevel(9, 7, 4) > 1,
                "lake refused to spill into a breached tunnel");
        assertTrue(hill.waterLevel(6, 7, 4) > 1,
                "spill did not travel along the tunnel floor");
        assertEquals(1, hill.waterLevel(11, 7, 4),
                "the breach converted the reservoir cell into disposable flow");
        assertEquals(1, hill.waterLevel(11, 9, 4),
                "lake was consumed instead of acting as an anchored source");
    }

    @Test
    void breachedReservoirKeepsEmittingAcrossSimulationTicks() {
        WorldChunk lake = chunk(0, 0);
        lake.blocks[WorldChunk.blockIndex(4, 5, 4)] = (byte) Block.AIR;
        lake.blocks[WorldChunk.blockIndex(4, 2, 4)] = (byte) Block.STONE;
        lake.setWaterLevel(4, 6, 4, 1);

        World.enqueueWaterUpdateImmediate(4, 6, 4);
        World.processWaterUpdates(1);
        int first = lake.waterLevel(4, 5, 4);
        World.processWaterUpdates(40);
        int second = lake.waterLevel(4, 5, 4);

        assertTrue(first > 1, "the breach did not emit its first flow cell");
        assertTrue(second > 1,
                "the reservoir stopped emitting after its first simulation tick");
        assertEquals(1, lake.waterLevel(4, 6, 4),
                "the breached reservoir cell was consumed");
    }

    @Test
    void breakingLakeBedThroughBlockFinderStartsSpillImmediately() {
        WorldChunk lake = chunk(0, 0);
        lake.blocks[WorldChunk.blockIndex(4, 5, 4)] = (byte) Block.STONE;
        lake.blocks[WorldChunk.blockIndex(4, 2, 4)] = (byte) Block.STONE;
        lake.setWaterLevel(4, 6, 4, 1);
        for (int i = 0; i < 1000; i++) {
            World.enqueueWaterUpdate(12, 12, 12);
        }

        BlockFinder.setBlockType(lake, 4, 5, 4, Block.AIR);

        assertEquals(1, lake.waterLevel(4, 6, 4),
                "breaking the bed consumed the generated reservoir");
        assertTrue(lake.waterLevel(4, 5, 4) > 1,
                "breaking the bed did not start the spill immediately");
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

    /**
     * Boundary continuity: two sloped level-1 cubes on opposite sides of a
     * chunk edge must place the shared lattice corner at the same height, or
     * their water meshes shear apart into a visible crack.
     */
    @Test
    void adjacentChunksShareLatticeCornersAcrossTheEdge() {
        WorldChunk left = chunk(0, 0);
        WorldChunk right = chunk(1, 0);
        left.setWaterLevel(WorldChunk.sizeX - 1, 6, 3, 1);
        left.setWaterLevel(WorldChunk.sizeX - 1, 6, 4, 1);
        right.setWaterLevel(0, 6, 3, 1);
        right.setWaterLevel(0, 6, 4, 1);

        float fromLeft = left.waterCornerHeight(WorldChunk.sizeX, 6, 4);
        float fromRight = right.waterCornerHeight(0, 6, 4);

        assertTrue(fromLeft > 0.0f, "shared corner collapsed to dry");
        assertEquals(fromLeft, fromRight, 0.0001f);
    }

    /**
     * Regression for the v0.8.0 crack: while the neighbour has not published
     * its water yet, the corner its columns contribute to would collapse to
     * a dry trough. Seam paving paves the unknown columns over with stable
     * heights equal to what the corner will have once the neighbour is read.
     */
    @Test
    void pavedCornersCoverAQuarterlyLoadedNeighbour() {
        WorldChunk left = chunk(0, 0);
        WorldChunk right = chunk(1, 0);
        left.setWaterLevel(WorldChunk.sizeX - 1, 6, 3, 1);
        left.setWaterLevel(WorldChunk.sizeX - 1, 6, 4, 1);
        right.setWaterLevel(0, 6, 3, 1);
        right.setWaterLevel(0, 6, 4, 1);
        assertTrue(left.hasIncompleteNeighbor());

        float withNeighbour = left.waterCornerHeight(WorldChunk.sizeX, 6, 4);

        // The neighbour drops back to un-published state, as during streaming.
        right.isGenerated = false;
        left.seamPaved = false;
        float unpaved = left.waterCornerHeight(WorldChunk.sizeX, 6, 4);
        assertTrue(unpaved < withNeighbour,
                "unpaved corner unexpectedly kept the full height (crack detector broke)");

        left.seamPaved = true;
        float paved = left.waterCornerHeight(WorldChunk.sizeX, 6, 4);
        assertEquals(withNeighbour, paved, 0.0001f);
        left.seamPaved = false;
    }

    /**
     * Flow dropped at a seam while the neighbour was ungenerated used to stay
     * dropped forever, leaving mismatched levels (and a mismatched corner) on
     * the two sides. The publish-path boundary replay re-drives exactly that
     * water across the edge; interior columns are untouched.
     */
    @Test
    void boundaryReplayCarriesSeamWaterIntoALateNeighbour() {
        WorldChunk left = chunk(0, 0);
        WorldChunk late = new WorldChunk(1, 0);   // registered, still generating
        World.registerChunk(late);
        chunks.add(late);

        left.blocks[WorldChunk.blockIndex(WorldChunk.sizeX - 2, 5, 4)] = (byte) Block.STONE;
        left.blocks[WorldChunk.blockIndex(WorldChunk.sizeX - 1, 5, 4)] = (byte) Block.STONE;
        left.setWaterLevel(WorldChunk.sizeX - 2, 6, 4, 8);
        left.setWaterLevel(WorldChunk.sizeX - 1, 6, 4, 7);
        World.enqueueWaterUpdate(WorldChunk.sizeX - 2, 6, 4);
        World.enqueueWaterUpdate(WorldChunk.sizeX - 1, 6, 4);
        World.processWaterUpdates(200);

        assertEquals(0, late.waterLevel(0, 6, 4),
                "seam water leaked into an ungenerated chunk");

        late.blocks[WorldChunk.blockIndex(0, 5, 4)] = (byte) Block.STONE;
        late.isGenerated = true;

        left.replayBoundaryColumns();
        World.processWaterUpdates(200);

        assertTrue(late.waterLevel(0, 6, 4) > 0,
                "boundary replay failed to carry seam water into the loaded neighbour");
    }
}
