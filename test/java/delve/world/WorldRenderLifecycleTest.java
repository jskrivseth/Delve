package delve.world;

import delve.core.Game;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldRenderLifecycleTest {
    private final ExecutorService originalPool = World.threadPool;
    private final FirstPersonCamera originalCamera = Game.GAME_CAMERA;
    private final boolean originalMemoryBound = Game.MEMORY_BOUND;
    private final int originalVboBudget = World.MAX_CHUNKS_TO_VBO;
    private final int originalVboCount = World.VBO_CHUNKS;
    private final ManualExecutor executor = new ManualExecutor();
    private final List<WorldChunk> registered = new ArrayList<>();
    private World world;
    private Method renderChunk;

    @BeforeEach
    void setUp() throws Exception {
        World.threadPool = executor;
        Game.MEMORY_BOUND = false;
        world = new World();
        Game.GAME_CAMERA = world.camera;
        World.MAX_CHUNKS_TO_VBO = 1;
        World.VBO_CHUNKS = 0;
        renderChunk = World.class.getDeclaredMethod("renderChunk",
                int.class, int.class, int.class, int.class, int.class, int.class);
        renderChunk.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        registered.forEach(World::unregisterChunk);
        World.threadPool = originalPool;
        Game.GAME_CAMERA = originalCamera;
        Game.MEMORY_BOUND = originalMemoryBound;
        World.MAX_CHUNKS_TO_VBO = originalVboBudget;
        World.VBO_CHUNKS = originalVboCount;
    }

    private void visit(WorldChunk chunk) throws Exception {
        if (!registered.contains(chunk)) {
            registered.add(chunk);
            World.registerChunk(chunk);
        }
        renderChunk.invoke(world, chunk.posX, chunk.posY, chunk.posX, chunk.posY, 0, 10);
    }

    @Test
    void exhaustedUploadBudgetDoesNotHideResidentMesh() throws Exception {
        DrawSpy chunk = new DrawSpy();
        chunk.isGenerated = true;
        chunk.isBuilt = true;
        Arrays.fill(chunk.blocks, (byte) Block.STONE);
        chunk.vboVertexHandle = 1;
        chunk.vaoHandle = 1;
        chunk.numVerts = 24;
        addGeneratedNeighbor(1999, 2000);
        addGeneratedNeighbor(2001, 2000);
        addGeneratedNeighbor(2000, 1999);
        addGeneratedNeighbor(2000, 2001);
        registered.add(chunk);
        World.registerChunk(chunk);
        chunk.buildMesh();
        assertTrue(chunk.hasPendingMesh());
        World.VBO_CHUNKS = World.MAX_CHUNKS_TO_VBO;
        visit(chunk);
        assertEquals(1, chunk.draws, "Upload throttling must not throttle existing terrain draws");
        assertEquals(0, chunk.uploads);
    }

    private void addGeneratedNeighbor(int x, int z) {
        WorldChunk neighbor = new WorldChunk(x, z);
        neighbor.isGenerated = true;
        registered.add(neighbor);
        World.registerChunk(neighbor);
    }

    @Test
    void queuedGenerationIsNotSubmittedAgainOnSubsequentFrames() throws Exception {
        WorldChunk chunk = new WorldChunk(2000, 2000);
        for (int frame = 0; frame < 4; frame++) {
            visit(chunk);
        }
        assertEquals(1, executor.tasks.size(), "A queued generator must reserve its chunk");
    }

    @Test
    void queuedInitialMeshIsNotSubmittedAgainOnSubsequentFrames() throws Exception {
        WorldChunk chunk = new WorldChunk(2000, 2000);
        chunk.isGenerated = true;
        for (int frame = 0; frame < 4; frame++) {
            visit(chunk);
        }
        assertEquals(1, executor.tasks.size(), "Initial meshes need the same queue guard as refreshes");
    }

    @Test
    void meshBuildRetryReleasesBuildingFlagWhenNeighborsAreNotReady() {
        int oldLowerX = World.CURRENT_BOUND_XL;
        int oldUpperX = World.CURRENT_BOUND_XU;
        int oldLowerZ = World.CURRENT_BOUND_YL;
        int oldUpperZ = World.CURRENT_BOUND_YU;
        try {
            World.CURRENT_BOUND_XL = -2;
            World.CURRENT_BOUND_XU = 2;
            World.CURRENT_BOUND_YL = -2;
            World.CURRENT_BOUND_YU = 2;
            WorldChunk chunk = new WorldChunk(0, 0);
            chunk.isGenerated = true;
            chunk.isBuilding = true;

            chunk.buildMesh();

            assertTrue(chunk.meshIsStale);
            assertFalse(chunk.isBuilding,
                    "A failed neighbor-readiness attempt must be retryable next frame");
        } finally {
            World.CURRENT_BOUND_XL = oldLowerX;
            World.CURRENT_BOUND_XU = oldUpperX;
            World.CURRENT_BOUND_YL = oldLowerZ;
            World.CURRENT_BOUND_YU = oldUpperZ;
        }
    }

    private static final class DrawSpy extends WorldChunk {
        int draws;
        int uploads;

        DrawSpy() {
            super(2000, 2000);
        }

        @Override
        public void buildVBO() {
            uploads++;
        }

        @Override
        public void drawMesh() {
            draws++;
        }
    }

    @Test
    void terrainBehindAnUnfinishedCellIsHiddenLocallyNotByGlobalCurtain() throws Exception {
        // Centre chunk (2000,2000); subject sits six rings north of it.
        // Frustum culling is orthogonal to what is under test and would
        // otherwise veto the draws for reasons of its own.
        boolean cullChunks = Game.OPT_CULL_CHUNKS;
        Game.OPT_CULL_CHUNKS = false;
        try {
            DrawSpy far = readyChunkAt(2000, 2006);
            // Nobody between the camera and it: hold it back, so it cannot look
            // like an island floating over a hole.
            visit(far, 6);
            assertEquals(0, far.draws, "unsupported terrain must wait");

            // Fill in the cell on its camera side: it may draw now, even though
            // rings elsewhere are still incomplete -- that is the point of
            // local masking, and what a whole-world frontier got wrong.
            DrawSpy support = readyChunkAt(2000, 2005);
            visit(far, 6);
            assertEquals(1, far.draws, "terrain behind finished neighbours must draw");

            // The near field is never withheld, however ragged things are
            // beyond it -- a hole behind the player must not darken the ground
            // they are standing on.
            DrawSpy foothold = readyChunkAt(2003, 2000);
            visit(foothold, 3);
            assertEquals(1, foothold.draws, "near-field terrain must always draw");
        } finally {
            Game.OPT_CULL_CHUNKS = cullChunks;
        }
    }

    private DrawSpy readyChunkAt(int x, int z) {
        DrawSpy spy = new DrawSpy();
        spy.posX = x;
        spy.posY = z;
        spy.isGenerated = true;
        spy.isBuilt = true;
        spy.vboVertexHandle = 1;
        spy.vaoHandle = 1;
        spy.numVerts = 24;
        Arrays.fill(spy.blocks, (byte) Block.STONE);
        registered.add(spy);
        World.registerChunk(spy);
        return spy;
    }

    private void visit(WorldChunk chunk, int ring) throws Exception {
        renderChunk.invoke(world, chunk.posX, chunk.posY, 2000, 2000, ring, 20);
    }

    @Test
    void fadeInGetsSlowerFurtherFromThePlayer() {
        float near = WorldChunk.fadeInRingScale(1);
        float mid = WorldChunk.fadeInRingScale(8);
        float far = WorldChunk.fadeInRingScale(16);
        float rim = WorldChunk.fadeInRingScale(32);

        assertTrue(near < mid, "emergence must be quicker close to the player");
        assertTrue(mid < far, "and slower with distance");
        assertTrue(far <= rim, "rising monotonically");
        assertTrue(rim <= Game.OPT_CHUNK_FADE_RING_MAX_SCALE, "but bounded");
        // Quadratic, not linear: doubling the ring more than doubles the delay.
        assertTrue(WorldChunk.fadeInRingScale(16) - WorldChunk.fadeInRingScale(8)
                > WorldChunk.fadeInRingScale(8) - WorldChunk.fadeInRingScale(0));

        float coefficient = Game.OPT_CHUNK_FADE_RING_SQUARED;
        try {
            Game.OPT_CHUNK_FADE_RING_SQUARED = 0f;
            assertEquals(1.0f, WorldChunk.fadeInRingScale(32),
                    "the distance term must be switchable off");
        } finally {
            Game.OPT_CHUNK_FADE_RING_SQUARED = coefficient;
        }
    }

    @Test
    void sweepRingKeepsClearanceBeyondTheVisibleSet() {
        // Sweeping inside the drawn radius made edge chunks blink in and out of
        // destroy fade; the keep ring must sit past the draw distance and the
        // render loop's fade-out band.
        assertTrue(WorldInactiveChunkSweeperThread.keepRadius(10, 1) > 10 + 3,
                "keep ring must clear the fade band");
        assertTrue(WorldInactiveChunkSweeperThread.keepRadius(46, 1) > 46 + 3);
        assertEquals(30, WorldInactiveChunkSweeperThread.keepRadius(10, 3),
                "an explicit multiplier still wins over the margin");
        assertTrue(WorldInactiveChunkSweeperThread.keepRadius(10, 0) > 10 + 3,
                "a zero multiplier must not shrink the keep ring below draw distance");
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
