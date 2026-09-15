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
