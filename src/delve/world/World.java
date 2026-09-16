/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package delve.world;

import static delve.world.BlockFinder.pickerRay;
import static delve.world.WorldChunk.sizeX;
import static delve.world.WorldChunk.sizeZ;

import delve.core.Game;
import delve.core.Vector3d;
import delve.core.Vector;
import delve.core.FrameStats;
import delve.core.Util;
import delve.core.MathHelper;
import delve.render.Renderer;
import delve.save.Serializer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.opengl.GL12;
// LWJGL vector imports replaced with JOML
import org.joml.Vector2f;
import org.joml.Vector3f;
import java.awt.image.BufferedImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import java.util.*;
import java.io.*;

/**
 *
 * @author Jesse
 */
public class World {

    /*
     * Properties
     */
    public static long WORLD_SEED;
    public static int WORLD_PRESET = WorldPreset.EARTH;
    public FirstPersonCamera camera;
    public static int sizeX = 4096;
    public static int sizeY = 4096;
    public static ByteBuffer worldTexture;
    public static List<ByteBuffer> subTextures;
    /*
     * Counters and flags
     */
    public static volatile boolean SWEEPER_IS_SLEEPING = true;
    public static volatile boolean WAKE_SWEEPER = true;
    public static int MAX_CHUNKS_TO_SWEEP = 8;  //Max chunks to sweep per pass
    public static int MAX_CHUNKS_TO_BUILD = 12;  //Max chunk meshes to build per frame
    /** Slots reserved beyond the ready frontier so inner churn (new arrivals,
     *  late spawns) can never spend the whole per-frame build budget in
     *  nearest-first ring order while named 'u' stragglers starve at the
     *  horizon -- the starvation the frontier-block telemetry named. */
    private static final int FRONTIER_RESERVED_BUILDS = 6;

    private static int buildBudgetFor(int radius) {
        if (radius <= lastReadyFrontier && lastReadyFrontier < effectiveRenderDistance() - 1) {
            return MAX_CHUNKS_TO_BUILD - FRONTIER_RESERVED_BUILDS;
        }
        return MAX_CHUNKS_TO_BUILD;
    }

    /** Cheap pre-check of buildMesh's precondition: builds submitted without
     *  generated cardinals bounce right back, burning a slot to accomplish
     *  nothing while the frontier waits. */
    private static boolean cardinalNeighborsGenerated(int i, int j) {
        if (i > CURRENT_BOUND_XL) {
            WorldChunk n = getChunk(i - 1, j);
            if (n != null && !n.isGenerated) {
                return false;
            }
        }
        if (i < CURRENT_BOUND_XU) {
            WorldChunk n = getChunk(i + 1, j);
            if (n != null && !n.isGenerated) {
                return false;
            }
        }
        if (j > CURRENT_BOUND_YL) {
            WorldChunk n = getChunk(i, j - 1);
            if (n != null && !n.isGenerated) {
                return false;
            }
        }
        if (j < CURRENT_BOUND_YU) {
            WorldChunk n = getChunk(i, j + 1);
            if (n != null && !n.isGenerated) {
                return false;
            }
        }
        return true;
    }

    public static int MAX_CHUNKS_TO_GEN = 8;  //Max chunks to try to generate per frame
    /**
     * Meshes promoted to the GPU per update. Eight was measured as the limiter
     * at maximum draw distance: geometry was being built faster than it could
     * be handed to the card, so terrain sat waiting to appear. Raised rather
     * than replaced with a smarter curtain -- uploads are the cure, hiding is
     * only ever a disguise.
     */
    public static int MAX_CHUNKS_TO_VBO = 32;
    /** Uploads stop for the frame once this deadline passes (time-capped so
     *  bursty promotion can never eat the frame: multi-MB glBufferSubData is
     *  synchronous). At least MIN_CHUNKS_TO_VBO always upload for progress. */
    private static long vboUploadDeadlineNanos;
    private static int vboUploadedThisFrame;
    private static final int MIN_CHUNKS_TO_VBO = 1;
    private static int vboUploadFloor = MIN_CHUNKS_TO_VBO;
    public static volatile long frameVboSpendNanos;
    public static volatile long lastFrameVboSpendNanos;
    /** Milliseconds the current frame's upload slice is willing to spend. */
    public static long vboSliceMillisNow() {
        long remain = vboUploadDeadlineNanos - System.nanoTime();
        long spend = frameVboSpendNanos;
        return (spend + Math.max(0, remain)) / 1_000_000L;
    }
    /** Bytes of pending mesh data at which new mesh work is refused. */
    public static long meshAdmissionBarBytes() {
        return MAX_PENDING_MESH_BYTES - PENDING_ADMISSION_MARGIN;
    }
    private static int GEN_CHUNKS = 0;
    private static int BUILT_CHUNKS = 0;
    public static int VBO_CHUNKS = 0;
    /**
     * Guards all voxel data. Mesh builders run concurrently under the read lock;
     * terrain generation and player edits take the write lock. A single world-wide
     * lock avoids the deadlock risk of per-chunk locks, since meshing a chunk has
     * to read its neighbours across borders.
     */
    public static final java.util.concurrent.locks.ReentrantReadWriteLock BLOCK_LOCK =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** Set by {@link Input} when the player clicks; consumed during update. */
    public static volatile boolean BREAK_BLOCK_REQUESTED = false;
    public static volatile boolean PLACE_BLOCK_REQUESTED = false;
    public static volatile boolean PICK_BLOCK_REQUESTED = false;
    public static boolean REBUILD_CHUNKS = false;
    public static int CURRENT_BOUND_XL = 0;
    public static int CURRENT_BOUND_XU = 0;
    public static int CURRENT_BOUND_YL = 0;
    public static int CURRENT_BOUND_YU = 0;
    /*
     * Data Structures
     */
    public static ArrayList<WorldChunk> chunks = new ArrayList<WorldChunk>();
    public static ArrayList<WorldChunk> destroyChunks = new ArrayList<WorldChunk>();
    public static ArrayList<WorldChunk> generateChunks = new ArrayList<WorldChunk>();
    /**
     * Generation and mesh workers seed water while the main thread drains it.
     * A concurrent queue prevents those legitimate publish-time additions from
     * corrupting ArrayDeque's internal state.
     */
    private static final ConcurrentLinkedDeque<Long> waterQueue =
            new ConcurrentLinkedDeque<Long>();
    /**
     * Wavefront of cells to reclaim after water was removed. Kept separate from
     * {@link #waterQueue} because it answers a different question: not "where
     * does this water flow?" but "which water lost its supply?".
     */
    private static final ArrayDeque<Long> waterDrainQueue = new ArrayDeque<Long>();
    /** Cells already queued this episode, so the wavefront terminates. */
    private static final HashSet<Long> drainQueued = new HashSet<Long>();
    /** Reusable primitive snapshot for the active-chunk water pass. */
    private static long[] globalWaterCells = new long[4096];
    private static int globalWaterCellCount;
    private static int globalWaterCellCursor;
    private static boolean globalWaterUpdateInProgress;
    /** Advance-one-per-global-slice clock backing weak-flow cadence. */
    private static byte waterEpoch;
    /** Water is intentionally much slower than the render loop. */
    public static int MAX_WATER_UPDATES = 12;
    /** Bounds active-world water work per simulation invoke. */
    public static int MAX_GLOBAL_WATER_UPDATES = 256;
    /**
     * Drain cells reclaimed per pass. Larger than the flow budget so breaching a
     * pool visibly recedes over a few ticks rather than hanging forever.
     */
    public static int MAX_WATER_DRAINS = 96;
    public static int MAX_WATER_DROP_DISTANCE = 8;
    public static long WATER_UPDATE_INTERVAL_NANOS = 180_000_000L;
    private static long nextWaterUpdateAtNanos;
    /*
     * State
     */
    /**
     * Generation and meshing are pure CPU work behind one shared read lock, so
     * three workers leave cores idle while fast flight demands ~50 chunks/s of
     * fresh terrain. Scale with the machine, but keep room for the render
     * thread and GC.
     */
    private static final int CHUNK_WORKER_COUNT = Math.max(3,
            Math.min(Math.max(4, Runtime.getRuntime().availableProcessors() - 2), 10));
    // A queue sized near one frame's demand turned every burst into a storm of
    // rejections, and every rejection threw away a frame's worth of submits.
    static final int MAX_QUEUED_CHUNK_TASKS = 512;
    private static final AtomicLong CHUNK_TASK_SEQUENCE = new AtomicLong();
    /** Cumulative counters for ChunkPipelineMonitor's throughput census. */
    static final AtomicLong SUBMITTED_CHUNK_TASKS = new AtomicLong();
    static final AtomicLong COMPLETED_CHUNK_TASKS = new AtomicLong();
    /** Ring up to which the last frame was willing to draw. */
    public static volatile int lastReadyFrontier = -1;
    /** Publication-wave diagnostics shown in the F6 overlay. */
    public static volatile int lastPublicationRadius = 4;
    public static volatile int heldForPublicationThisFrame;
    public static volatile int newlyPublishedThisFrame;
    public static ExecutorService threadPool = createThreadPool();
    private static ExecutorService maintenancePool = createMaintenancePool();
    private static final long SWEEP_INTERVAL_NANOS = 250_000_000L;
    private static long nextSweepAtNanos;

    private static ExecutorService createThreadPool() {
        return new ThreadPoolExecutor(
                CHUNK_WORKER_COUNT,
                CHUNK_WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                if (getQueue().size() >= MAX_QUEUED_CHUNK_TASKS) {
                    purgeObsoleteQueuedTasks();
                    if (getQueue().size() >= MAX_QUEUED_CHUNK_TASKS) {
                        throw new RejectedExecutionException("chunk task queue is full");
                    }
                }
                super.execute(command);
            }
        };
    }

    private static ExecutorService createMaintenancePool() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "chunk-maintenance");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Kinds of queued work, so an overflow can roll back the right chunk flag.
     */
    static final int TASK_GENERATION = 0;
    static final int TASK_MESH = 1;
    static final int TASK_OTHER = 2;
    /** Innermost rings that must load even under memory pressure. */
    private static final int ESSENTIAL_LOAD_RADIUS = 3;
    /**
     * Effective view radius in chunks: normally the player's setting, pulled in
     * by the memory governor when the heap runs short and walked back out once
     * there is headroom. Scaling the ring is far kinder than the old cliff
     * where MEMORY_BOUND simply refused to create chunks and the world went
     * dark around the player.
     */
    static int activeRenderRadius = Game.OPT_DRAW_DISTANCE;
    /**
     * Headroom ratios commanding shrink/grow. The release threshold sits just
     * above the shrink threshold, not far above it: a JVM that has grown its
     * heap keeps that heap populated, so "available" rarely climbs back to a
     * quarter of max. Requiring a large recovery before releasing the horizon
     * left the radius pinned at its floor long after the pressure passed.
     */
    private static final float HEADROOM_SHRINK_BELOW = 0.12f;
    private static final float HEADROOM_GROW_ABOVE = 0.14f;
    /** Teardown backlog that counts as pressure: queued, not yet reclaimed. */
    private static final int TEARDOWN_SQUEEZE = 8192;
    private static final int TEARDOWN_RELAX_LIMIT = TEARDOWN_SQUEEZE / 4;
    /** Never blind the player completely, however tight memory gets. */
    private static final int GOVERNOR_MIN_RADIUS = 6;
    /** Minimum spacing between "view distance pulled in" toasts. */
    private static final long GOVERNOR_TOAST_COOLDOWN_NANOS = 20_000_000_000L;
    private static final long GOVERNOR_SAMPLE_NANOS = 200_000_000L;
    private static long nextGovernorSampleAtNanos;
    /** True while the governor holds the horizon in shorter than requested. */
    private static boolean governorHeldIn;
    private static long lastGovernorToastAtNanos;

    /**
     * The radius everything should actually use: the player's setting, or the
     * shorter distance the memory governor has settled on. Load, sweep, fog and
     * the far plane must agree, or a shortened horizon shows up as a hard cut
     * instead of disappearing into haze.
     */
    public static int effectiveRenderDistance() {
        int wanted = Math.max(1, Game.OPT_DRAW_DISTANCE);
        return Math.min(Math.max(1, activeRenderRadius), wanted);
    }

    /** True while the memory governor holds the horizon shorter than asked. */
    public static boolean memoryGovernorHolding() {
        return governorHeldIn;
    }

    /**
     * Feeds idle generation capacity into only the next incomplete ring.
     * Skipping ahead produced scattered islands at the horizon; completing
     * one ring at a time preserves the outward-painting contract.
     */
    static void prewarmLookahead() {
        if (Game.GAME_CAMERA == null
                || inChunkSubmitBackoff()
                || pendingChunkTasks() > MAX_QUEUED_CHUNK_TASKS / 8
                || busyChunkWorkers() > Math.max(2, totalChunkWorkers() / 3)) {
            return;
        }
        int want = effectiveRenderDistance();
        int ring = Math.max(0, lastReadyFrontier + 1);
        if (ring > want || !canLoadChunk(ring)) {
            return;
        }
        int ci = (int) Math.floor(Game.GAME_CAMERA.position.x / WorldChunk.sizeX);
        int cj = (int) Math.floor(Game.GAME_CAMERA.position.z / WorldChunk.sizeZ);
        int budget = 48;
        for (int i = ci - ring; i <= ci + ring && budget > 0; i++) {
            for (int j = cj - ring; j <= cj + ring && budget > 0; j++) {
                if (i != ci - ring && i != ci + ring && j != cj - ring && j != cj + ring) {
                    continue;
                }
                if (i < CURRENT_BOUND_XL || i > CURRENT_BOUND_XU
                        || j < CURRENT_BOUND_YL || j > CURRENT_BOUND_YU) {
                    continue;
                }
                WorldChunk chunk = getChunk(i, j);
                if (chunk == null) {
                    chunk = new WorldChunk(i, j);
                    synchronized (chunks) {
                        chunks.add(chunk);
                    }
                    registerChunk(chunk);
                }
                if (chunk.isGenerating || chunk.isGenerated || chunk.isBuilt
                        || chunk.isReady() || chunk.isZombie) {
                    continue;
                }
                chunk.isGenerating = true;
                chunk.isGeneratingSince = System.nanoTime();
                try {
                    submitChunkTask(new WorldChunkLoadThread(chunk), chunk, TASK_GENERATION);
                    budget--;
                } catch (RejectedExecutionException e) {
                    chunk.isGenerating = false;
                    return;
                }
            }
        }
    }

    /** Depth the chunk task queue may reach before submits are refused. */
    public static int maxQueuedChunkTasks() {
        return MAX_QUEUED_CHUNK_TASKS;
    }
    /**
     * After a rejected submission the queue is by definition full, so retrying
     * the same chunk next frame just wastes CPU: thousands of still-unbuilt
     * chunks at ~1000 fps meant ~300k rejected submits per second against ~3k
     * completions, which is pure GC churn while nothing progressed. Pausing
     * submissions briefly lets the backlog drain; VBO promotion is unaffected.
     */
    private static final long SUBMIT_BACKOFF_NANOS = 120_000_000L;
    private static long submitBackoffUntilNanos;
    /**
     * Heap ceiling for meshes built but not yet uploaded. Measured: pending
     * geometry routinely held a quarter gigabyte mid-flight (peak 441 MB),
     * duplicating data the card had not been given yet, while terrain waited to
     * appear. New builds pause while the budget is full; nearest-first
     * promotion drains it from around the player outward.
     */
    private static final long MAX_PENDING_MESH_BYTES = 272L * 1024L * 1024L;
    /** Admission stops this far below the ceiling so builds already running
     *  when the check last passed (six workers x measured worst-case mesh)
     *  still land under the cap instead of booking past it. */
    private static final long PENDING_ADMISSION_MARGIN = 48L * 1024L * 1024L;
    private static long lastQueuePurgeAtNanos;
    private static int queuePriorityCenterX = Integer.MIN_VALUE;
    private static int queuePriorityCenterZ = Integer.MIN_VALUE;
    static final AtomicLong PURGED_CHUNK_TASKS = new AtomicLong();

    static void submitChunkTask(Runnable task, WorldChunk chunk) {
        submitChunkTask(task, chunk, chunk == null ? TASK_OTHER : TASK_MESH);
    }

    static void submitChunkTask(Runnable task, WorldChunk chunk, int kind) {
        int priority = Integer.MAX_VALUE;
        if (chunk != null && Game.GAME_CAMERA != null) {
            int centerX = (int) Math.floor(Game.GAME_CAMERA.position.x / WorldChunk.sizeX);
            int centerZ = (int) Math.floor(Game.GAME_CAMERA.position.z / WorldChunk.sizeZ);
            priority = taskPriority(chunk, centerX, centerZ);
        }
        SUBMITTED_CHUNK_TASKS.incrementAndGet();
        threadPool.execute(new PrioritizedChunkTask(task, priority,
                CHUNK_TASK_SEQUENCE.getAndIncrement(), chunk, kind));
    }

    /**
     * Strict ring-first priority. The squared-distance suffix makes the wave
     * rounder within a Chebyshev ring without allowing any farther ring to
     * leapfrog a nearer one.
     */
    static int taskPriority(WorldChunk chunk, int centerX, int centerZ) {
        int dx = Math.abs(chunk.posX - centerX);
        int dz = Math.abs(chunk.posY - centerZ);
        int ring = Math.max(dx, dz);
        int withinRing = Math.min(4095, dx * dx + dz * dz);
        return ring * 4096 + withinRing;
    }

    /**
     * A priority captured when a task was submitted becomes wrong as soon as
     * the player crosses a chunk boundary. Rebuild the small bounded queue at
     * the new center so work beside the player always outranks work left
     * behind. Tasks already running finish normally.
     */
    private static void recenterQueuedTasks(int centerX, int centerZ) {
        if (centerX == queuePriorityCenterX && centerZ == queuePriorityCenterZ) {
            return;
        }
        queuePriorityCenterX = centerX;
        queuePriorityCenterZ = centerZ;
        if (!(threadPool instanceof ThreadPoolExecutor)) {
            return;
        }
        java.util.concurrent.BlockingQueue<Runnable> queue =
                ((ThreadPoolExecutor) threadPool).getQueue();
        Object[] snapshot = queue.toArray();
        int purged = 0;
        for (Object entry : snapshot) {
            if (!(entry instanceof PrioritizedChunkTask)) {
                continue;
            }
            PrioritizedChunkTask task = (PrioritizedChunkTask) entry;
            WorldChunk chunk = task.chunk;
            if (!queue.remove(task)) {
                continue;
            }
            if (chunk == null || chunk.isZombie || !isChunkInCurrentBounds(chunk)
                    || !isCurrentChunk(chunk)) {
                task.cancelQueued();
                purged++;
                continue;
            }
            task.priority = taskPriority(chunk, centerX, centerZ);
            queue.offer(task);
        }
        if (purged > 0) {
            PURGED_CHUNK_TASKS.addAndGet(purged);
        }
    }

    /**
     * Chunks no longer wanted must not squat in the queue: with the queue full
     * of terrain the camera has already flown past, every genuinely near submit
     * bounced off the cap and the visible frontier starved. Evict the obsolete
     * entries (rolling their flags back so they can be re-submitted if the
     * player doubles back) before declaring the queue full.
     */
    private static void purgeObsoleteQueuedTasks() {
        long now = System.nanoTime();
        if (now - lastQueuePurgeAtNanos < 20_000_000L) {
            return;
        }
        lastQueuePurgeAtNanos = now;
        if (!(threadPool instanceof ThreadPoolExecutor)) {
            return;
        }
        java.util.Iterator<Runnable> it =
                ((ThreadPoolExecutor) threadPool).getQueue().iterator();
        int purged = 0;
        while (it.hasNext()) {
            Object entry = it.next();
            if (!(entry instanceof PrioritizedChunkTask)) {
                continue;
            }
            PrioritizedChunkTask task = (PrioritizedChunkTask) entry;
            WorldChunk chunk = task.chunk;
            if (chunk == null || !(chunk.isZombie || !isChunkInCurrentBounds(chunk)
                    || !isCurrentChunk(chunk))) {
                continue;
            }
            it.remove();
            task.cancelQueued();
            purged++;
        }
        if (purged > 0) {
            PURGED_CHUNK_TASKS.addAndGet(purged);
        }
    }

    /**
     * Memory pressure stops the loaded halo from expanding, but never the
     * ground around the player. Refusing to create chunks everywhere while the
     * heap recovered left players standing on nothing (falling through a world
     * that could not rebuild itself for minutes).
     */
    static boolean canLoadChunk(int innerRadius) {
        return !Game.MEMORY_BOUND || innerRadius <= ESSENTIAL_LOAD_RADIUS;
    }

    /** True while rejected submissions have put the queue on a short timeout. */
    static boolean inChunkSubmitBackoff() {
        return System.nanoTime() < submitBackoffUntilNanos;
    }

    static void enterChunkSubmitBackoff() {
        submitBackoffUntilNanos = System.nanoTime() + SUBMIT_BACKOFF_NANOS;
    }

    public static int pendingChunkTasks() {
        return threadPool instanceof ThreadPoolExecutor
                ? ((ThreadPoolExecutor) threadPool).getQueue().size() : 0;
    }

    public static int busyChunkWorkers() {
        return threadPool instanceof ThreadPoolExecutor
                ? ((ThreadPoolExecutor) threadPool).getActiveCount() : 0;
    }

    public static int totalChunkWorkers() {
        return CHUNK_WORKER_COUNT;
    }

    /** This frame's build/gen submissions, for the flash-watch telemetry. */
    static int builtSubmittedThisFrame() {
        return BUILT_CHUNKS;
    }

    static int genSubmittedThisFrame() {
        return GEN_CHUNKS;
    }

    private static final class PrioritizedChunkTask
            implements Runnable, Comparable<PrioritizedChunkTask> {
        private final Runnable task;
        private volatile int priority;
        private final long sequence;
        private final WorldChunk chunk;
        private final int kind;

        PrioritizedChunkTask(Runnable task, int priority, long sequence,
                             WorldChunk chunk, int kind) {
            this.task = task;
            this.priority = priority;
            this.sequence = sequence;
            this.chunk = chunk;
            this.kind = kind;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                COMPLETED_CHUNK_TASKS.incrementAndGet();
                if (kind == TASK_OTHER) {
                    ChunkPipelineMonitor.markTaskEnd();
                } else {
                    ChunkPipelineMonitor.markChunkTaskEnd();
                }
            }
        }

        /**
         * Drops a task that will never run. Its chunk's in-flight flags were
         * set on the faith that this task would clear them; without rolling
         * them back the chunk is skipped by the renderer forever.
         */
        void cancelQueued() {
            if (chunk == null) {
                return;
            }
            if (kind == TASK_GENERATION) {
                chunk.isGenerating = false;
                chunk.isGeneratingSince = 0;
            } else if (kind == TASK_MESH) {
                chunk.isBuilding = false;
                chunk.isRefreshing = false;
                chunk.isBuildingSince = 0;
            }
        }

        @Override
        public int compareTo(PrioritizedChunkTask other) {
            int byDistance = Integer.compare(priority, other.priority);
            return byDistance != 0 ? byDistance : Long.compare(sequence, other.sequence);
        }
    }

    /**
     * Clears all world state so a different save can be loaded in the same
     * session, and seeds terrain generation.
     */
    public static void reset(long seed) {
        reset(seed, WorldPreset.EARTH);
    }

    public static void reset(long seed, int worldPreset) {
        shutdown();
        chunkIndex.clear();
        chunks.clear();
        destroyChunks.clear();
        WorldChunk.resetPendingMeshAccounting();
        activeRenderRadius = Game.OPT_DRAW_DISTANCE;
        governorHeldIn = false;
        submitBackoffUntilNanos = 0;
        GEN_CHUNKS = 0;
        BUILT_CHUNKS = 0;
        VBO_CHUNKS = 0;
        waterQueue.clear();
        waterDrainQueue.clear();
        drainQueued.clear();
        globalWaterCellCount = 0;
        globalWaterCellCursor = 0;
        globalWaterUpdateInProgress = false;
        nextWaterUpdateAtNanos = 0L;
        SWEEPER_IS_SLEEPING = true;
        WAKE_SWEEPER = true;
        nextSweepAtNanos = 0L;
        BREAK_BLOCK_REQUESTED = false;
        PLACE_BLOCK_REQUESTED = false;
        PICK_BLOCK_REQUESTED = false;
        WORLD_SEED = seed;
        WORLD_PRESET = WorldPreset.clamp(worldPreset);
        // Reseeding the noise generator is what actually makes a seed mean
        // something. Offsetting the sample coordinates instead cannot work,
        // because the generator's permutation table is itself the field being
        // sampled, and it was previously randomised on every launch.
        PerlinNoiseGenerator.reseed(seed);
        threadPool = createThreadPool();
        maintenancePool = createMaintenancePool();
    }

    /**
     * Returns whether a queued chunk task still belongs to the active view.
     * Fast flight can leave many generation/mesh tasks queued behind the
     * camera; those tasks should be discarded when they finally reach a worker.
     */
    static boolean isChunkInCurrentBounds(WorldChunk chunk) {
        return chunk != null
                && chunk.posX >= CURRENT_BOUND_XL && chunk.posX <= CURRENT_BOUND_XU
                && chunk.posY >= CURRENT_BOUND_YL && chunk.posY <= CURRENT_BOUND_YU;
    }

    static boolean isCurrentChunk(WorldChunk chunk) {
        return chunk != null && getChunk(chunk.posX, chunk.posY) == chunk;
    }

    /** Stops worker threads and releases GPU meshes for the current world. */
    public static void shutdown() {
        threadPool.shutdownNow();
        maintenancePool.shutdownNow();
        try {
            threadPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            maintenancePool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (WorldChunk chunk : new ArrayList<>(chunks)) {
            if (chunk != null) {
                Renderer.deleteChunkMesh(chunk);
            }
        }
        chunks.clear();
        chunkIndex.clear();
        destroyChunks.clear();
    }

    public World() {
        Vector3f position = new Vector3f(Game.PLAYER_START_POSITION);
        camera = new FirstPersonCamera(position.x, position.y, position.z);
        subTextures = new ArrayList<ByteBuffer>();
    }

    public World(Vector3f position) {
        camera = new FirstPersonCamera(position.x, position.y, position.z);
        subTextures = new ArrayList<ByteBuffer>();
    }

    /**
     * Spatial index for chunk lookup. getChunk is called for every ambient
     * occlusion sample and every collision voxel, so a linear scan of the chunk
     * list showed up directly in frame time.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, WorldChunk> chunkIndex =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long chunkKey(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xFFFFFFFFL);
    }

    public static void registerChunk(WorldChunk chunk) {
        chunkIndex.put(chunkKey(chunk.posX, chunk.posY), chunk);
    }

    public static void unregisterChunk(WorldChunk chunk) {
        ChunkVisibilityWatch.pulse('X');
        ChunkVisibilityWatch.observe(null, chunk.posX, chunk.posY, ChunkVisibilityWatch.FORGET);
        chunkIndex.remove(chunkKey(chunk.posX, chunk.posY), chunk);
    }

    public static WorldChunk getChunk(int x, int y) {
        return chunkIndex.get(chunkKey(x, y));
    }

    public static int getChunkIndex(int x, int y) {
        for (int i = 0; i < World.chunks.size(); i++) {
            WorldChunk chunk = World.chunks.get(i);
            if (chunk != null && chunk.posX == x && chunk.posY == y) {
                return i;
            }
        }
        return -1;
    }

    public static int getChunkIndex(WorldChunk chunk) {
        return World.chunks.indexOf(chunk);
    }

    public void loadTextures() {
        // Terrain atlas is loaded by Renderer via the STB-backed Texture class.
    }

    public void loadModels() {
        // Legacy OBJ/display-list model path removed with the fixed-function pipeline.
    }

    public static float getHeightAt(int x, int y) {
        double xPos = x / 128.0;
        double yPos = y / 128.0;
        // Must match WorldChunk.generate() exactly, or the spawn height lands in
        // the wrong place and the player starts buried.
        double v = PerlinNoiseGenerator.getNoise(xPos, yPos, 3, 3.25f, WorldChunk.sizeY);
        v += 1.0f;
        int height = (int) (v * (WorldChunk.sizeY / 2.0));
        if (height > WorldChunk.sizeY - 4) {
            height = WorldChunk.sizeY - 4;
        }
        if (height < 1) {
            height = 1;
        }
        return Math.max(height, WorldChunk.SEA_LEVEL);
    }

    /**
     * Freed chunks per second, and the most any single frame may pay for.
     * Sized to outrun sustained churn at maximum draw distance (measured
     * condemnation of ~1500 chunks/s there); a pinned heap raises both, since
     * freeing faster beats smoothing the frame in that case.
     */
    private static double teardownTokens;
    private static long lastTeardownRefillAtNanos;

    private static void refillTeardownTokens() {
        long now = System.nanoTime();
        double perSecond = Game.MEMORY_BOUND ? 6000.0 : 3500.0;
        int burst = Game.MEMORY_BOUND ? 128 : 64;
        if (lastTeardownRefillAtNanos == 0) {
            lastTeardownRefillAtNanos = now;
            teardownTokens = burst;
            return;
        }
        double elapsed = (now - lastTeardownRefillAtNanos) / 1_000_000_000.0;
        lastTeardownRefillAtNanos = now;
        teardownTokens = Math.min(burst, teardownTokens + elapsed * perSecond);
    }

    public void update() {
        BUILT_CHUNKS = 0;
        GEN_CHUNKS = 0;
        VBO_CHUNKS = 0;
        vboUploadedThisFrame = 0;
        // Upload budget follows PENDING PRESSURE, not framerate. Coupling it
        // to FPS was the mistake: at 40 fps the slice shrank to 0.6 ms, which
        // at ~0.25 ms/upload yielded ~2 uploads/frame (~150/s) while thousands
        // waited -- terrain arrival starved, the pending pile crossed the mesh
        // admission bar, mesh submits halted, and the whole worldstream froze
        // behind its own queue. When lots is queued, spending MORE frame time
        // on geometry is the fastest route back to calm frames.
        long pendingMB = WorldChunk.pendingMeshBytesTotal() >>> 20;
        long sliceNanos;
        int floorThisFrame;
        if (pendingMB <= 8) {
            sliceNanos = 400_000L;
            floorThisFrame = MIN_CHUNKS_TO_VBO;
        } else if (pendingMB <= 40) {
            sliceNanos = 1_500_000L;
            floorThisFrame = 3;
        } else {
            sliceNanos = Math.min(4_500_000L, 1_500_000L + (pendingMB - 40) * 50_000L);
            floorThisFrame = pendingMB > 96 ? 12 : 6;
        }
        vboUploadDeadlineNanos = System.nanoTime() + sliceNanos;
        vboUploadFloor = floorThisFrame;
        lastFrameVboSpendNanos = frameVboSpendNanos;
        frameVboSpendNanos = 0;
        tuneRenderRadiusToMemory();
        serializeAndFreeInactiveChunks();
        long now = System.nanoTime();
        if (now >= nextWaterUpdateAtNanos) {
            processGlobalWaterUpdates();
            nextWaterUpdateAtNanos = now + WATER_UPDATE_INTERVAL_NANOS;
        }
        pickSelectedBlock();
    }

    /**
     * Fits the view radius to the memory actually available.
     *
     * Pulling the ring in is graded (roughly an eighth per sample down to a
     * floor) and releasing it is slow (one ring per sample after a dwell),
     * so the horizon commits to a value instead of chasing the GC sawtooth.
     * A large teardown backlog also counts as squeeze: those chunks are
     * memory the sweep has promised but not yet surrendered, which is
     * exactly the state fast travel pushes the game into.
     */
    /**
     * Rolling worst-headroom window. Available memory is a sawtooth: it dips
     * before each collection and rebounds after. Deciding on the live sample
     * let that noise walk the horizon in and out every few seconds, and rings
     * crossing the keep boundary fade-out-then-cancel in a loop -- visible
     * flicker far out. Shrinking reacts to the WORST sample of the last two
     * seconds (never complacent), growing additionally demands a dwell since
     * any radius change, so the horizon commits to a value and stays.
     */
    private static final int GOVERNOR_WINDOW = 10;
    private static final long GOVERNOR_GROW_DWELL_NANOS = 1_000_000_000L;
    private static final float[] headroomWindow = new float[GOVERNOR_WINDOW];
    private static int headroomWindowFill;
    private static int headroomWindowNext;
    private static long lastRadiusChangeAtNanos;
    /** Pacing protects against sensor noise -- never against intent. */
    private static int lastOptDrawDistance = -1;

    private static void tuneRenderRadiusToMemory() {
        long now = System.nanoTime();
        if (now < nextGovernorSampleAtNanos) {
            return;
        }
        nextGovernorSampleAtNanos = now + GOVERNOR_SAMPLE_NANOS;

        int wanted = Math.max(1, Game.OPT_DRAW_DISTANCE);
        int floor = Math.min(GOVERNOR_MIN_RADIUS, wanted);
        float headroom = (float) Util.getAvailableMemory() / (float) Util.getMaxMemory();
        headroomWindow[headroomWindowNext] = headroom;
        headroomWindowNext = (headroomWindowNext + 1) % GOVERNOR_WINDOW;
        if (headroomWindowFill < GOVERNOR_WINDOW) {
            headroomWindowFill++;
        }
        float worst = headroom;
        for (int i = 0; i < headroomWindowFill; i++) {
            worst = Math.min(worst, headroomWindow[i]);
        }
        int queuedTeardown;
        synchronized (destroyChunks) {
            queuedTeardown = destroyChunks.size();
        }
        boolean squeezed = worst < HEADROOM_SHRINK_BELOW || queuedTeardown > TEARDOWN_SQUEEZE;
        boolean dwelling = now - lastRadiusChangeAtNanos < GOVERNOR_GROW_DWELL_NANOS;
        boolean relaxed = !dwelling && worst > HEADROOM_GROW_ABOVE
                && queuedTeardown < TEARDOWN_RELAX_LIMIT;

        int current = activeRenderRadius;
        if (wanted != lastOptDrawDistance) {
            lastOptDrawDistance = wanted;
            if (wanted > current) {
                // The player raised the horizon on purpose: obey at once.
                // The window/dwell pacing exists for GC sawtooth noise, and
                // throttling an explicit choice is how "set 32, wait forever
                // for it to paint" happened.
                current = wanted;
                lastRadiusChangeAtNanos = 0;
            }
        }
        if (current > wanted) {
            // The player lowered the setting; obey immediately.
            current = wanted;
        }
        if (squeezed) {
            int reduced = Math.max(floor, current - Math.max(1, current / 8));
            if (reduced != current) {
                lastRadiusChangeAtNanos = now;
            }
            current = reduced;
        } else if (relaxed && current < wanted) {
            int grown = Math.min(wanted, current + 2);
            if (grown != current) {
                lastRadiusChangeAtNanos = now;
            }
            current = grown;
        }

        boolean heldIn = current < wanted;
        boolean steppedDown = heldIn && current <= activeRenderRadius - 2;
        if (heldIn && (steppedDown || !governorHeldIn)
                && now - lastGovernorToastAtNanos > GOVERNOR_TOAST_COOLDOWN_NANOS) {
            lastGovernorToastAtNanos = now;
            String why = headroom < HEADROOM_SHRINK_BELOW ? "low memory" : "chunk teardown backlog";
            delve.render.Toast.show("View distance " + wanted + " -> " + current + " (" + why + ")");
        } else if (!heldIn && governorHeldIn) {
            lastGovernorToastAtNanos = now;
            delve.render.Toast.show("View distance restored to " + wanted);
        }
        governorHeldIn = heldIn;
        if (current != activeRenderRadius) {
            activeRenderRadius = current;
            // The far plane is derived from the view radius; without this the
            // clip plane would keep reaching out to the old horizon.
            if (Game.INSTANCE != null) {
                Game.INSTANCE.setupPerspective();
            }
        }
    }

    private static long waterKey(int x, int y, int z) {
        return (((long) x & 0x1FFFFFL) << 43)
                | (((long) y & 0x7FL) << 36)
                | ((long) z & 0x1FFFFFL);
    }

    private static int waterX(long key) { return (int) (key >> 43); }
    private static int waterY(long key) { return (int) ((key >> 36) & 0x7F); }
    private static int waterZ(long key) {
        return ((int) (key & 0x1FFFFFL) << 11) >> 11;
    }

    public static void enqueueWaterUpdate(int x, int y, int z) {
        if (y >= 0 && y < WorldChunk.sizeY) {
            waterQueue.addLast(waterKey(x, y, z));
        }
    }

    /**
     * Player edits must not wait behind terrain-water settling queued by chunk
     * generation. The update itself still fans out through the regular FIFO,
     * preserving deterministic simulation order after the immediate response.
     */
    public static void enqueueWaterUpdateImmediate(int x, int y, int z) {
        if (y >= 0 && y < WorldChunk.sizeY) {
            waterQueue.addFirst(waterKey(x, y, z));
        }
    }

    public static void clearWaterUpdates() {
        waterQueue.clear();
        waterDrainQueue.clear();
        drainQueued.clear();
        globalWaterCellCount = 0;
        globalWaterCellCursor = 0;
        globalWaterUpdateInProgress = false;
    }

    /**
     * Queues the neighbourhood of a cell whose water was taken away, so water
     * that only existed because of it is reclaimed instead of hanging in midair
     * (mutual lateral support keeps neighbouring flow cells artificially alive).
     *
     * Only flow water (levels 2..7) is reclaimed. Level 8 is an independent
     * source, and level 1 is generated sea/lake water, which is held in place by
     * the terrain it fills rather than by a source.
     */
    public static void enqueueWaterDrainNeighborhood(int x, int y, int z) {
        enqueueWaterDrain(x - 1, y, z);
        enqueueWaterDrain(x + 1, y, z);
        enqueueWaterDrain(x, y + 1, z);
        enqueueWaterDrain(x, y - 1, z);
        enqueueWaterDrain(x, y, z - 1);
        enqueueWaterDrain(x, y, z + 1);
    }

    private static void enqueueWaterDrain(int x, int y, int z) {
        if (y < 0 || y >= WorldChunk.sizeY) {
            return;
        }
        long key = waterKey(x, y, z);
        if (drainQueued.add(key)) {
            waterDrainQueue.addLast(key);
        }
    }

    /**
     * Reclaims one bounded slice of the drain wavefront. Fixed neighbour order
     * plus a visited set keeps the result identical for identical input.
     */
    private static int processWaterDrains(int budget) {
        int cleared = 0;
        int examined = 0;
        while (examined++ < budget && !waterDrainQueue.isEmpty()) {
            long key = waterDrainQueue.removeFirst();
            drainQueued.remove(key);
            int x = waterX(key), y = waterY(key), z = waterZ(key);
            int level = waterLevelAtWorld(x, y, z);
            if (level < 2 || level >= 8) {
                continue;   // dry, generated water, or an independent source
            }
            clearWaterCell(x, y, z);
            cleared++;
            enqueueWaterDrain(x - 1, y, z);
            enqueueWaterDrain(x + 1, y, z);
            enqueueWaterDrain(x, y + 1, z);
            enqueueWaterDrain(x, y - 1, z);
            enqueueWaterDrain(x, y, z - 1);
            enqueueWaterDrain(x, y, z + 1);
        }
        return cleared;
    }

    /** Empties one cell and re-meshes its chunk (and the seam, if it is on one). */
    private static void clearWaterCell(int x, int y, int z) {
        WorldChunk chunk = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                Math.floorDiv(z, WorldChunk.sizeZ));
        if (chunk == null || !chunk.isGenerated || chunk.waterLevels == null) {
            return;
        }
        int lx = Math.floorMod(x, WorldChunk.sizeX);
        int lz = Math.floorMod(z, WorldChunk.sizeZ);
        BLOCK_LOCK.writeLock().lock();
        try {
            chunk.setWaterLevel(lx, y, lz, 0);
        } finally {
            BLOCK_LOCK.writeLock().unlock();
        }
        if (lx == 0) {
            markNeighborMeshStale(Math.floorDiv(x, WorldChunk.sizeX) - 1,
                Math.floorDiv(z, WorldChunk.sizeZ));
        } else if (lx == WorldChunk.sizeX - 1) {
            markNeighborMeshStale(Math.floorDiv(x, WorldChunk.sizeX) + 1,
                Math.floorDiv(z, WorldChunk.sizeZ));
        }
        if (lz == 0) {
            markNeighborMeshStale(Math.floorDiv(x, WorldChunk.sizeX),
                Math.floorDiv(z, WorldChunk.sizeZ) - 1);
        } else if (lz == WorldChunk.sizeZ - 1) {
            markNeighborMeshStale(Math.floorDiv(x, WorldChunk.sizeX),
                Math.floorDiv(z, WorldChunk.sizeZ) + 1);
        }
    }

    private static void markNeighborMeshStale(int chunkX, int chunkZ) {
        WorldChunk neighbor = getChunk(chunkX, chunkZ);
        if (neighbor != null) {
            neighbor.meshIsStale = true;
        }
    }

    /** Runs a bounded, deterministic FIFO water pass; exposed for focused tests. */
    public static int processWaterUpdates(int budget) {
        processWaterDrains(MAX_WATER_DRAINS);
        int processed = 0;
        while (processed++ < budget && !waterQueue.isEmpty()) {
            long key = waterQueue.removeFirst();
            int x = waterX(key), y = waterY(key), z = waterZ(key);
                WorldChunk source = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                        Math.floorDiv(z, WorldChunk.sizeZ));
            if (source == null || !source.isGenerated || source.waterLevels == null) {
                continue;
            }
            int lx = Math.floorMod(x, WorldChunk.sizeX), lz = Math.floorMod(z, WorldChunk.sizeZ);
            int level = source.waterLevel(lx, y, lz);
            if (level == 0) {
                continue;
            }
            /*
             * Level 1 is terrain water: the basin it fills holds it, so it keeps
             * its level. When terrain underneath or beside it opens, it behaves
             * as an anchored reservoir source: emit reclaimable level-7 flow
             * without consuming the lake cell. This keeps generated lakes full
             * when dug into from below, while the emitted water still drains
             * normally after the breach is plugged.
             */
            if (level == 1) {
                boolean emitted = false;
                if (Block.isWaterReplaceable(blockTypeAtWorld(x, y - 1, z))
                        && spreadWater(x, y - 1, z, 7)) {
                    emitted = true;
                }
                // Spill through any adjacent cell water could actually escape
                // into: one with open space below it (a cliff lip or shaft),
                // or one covered overhead (a cave mouth or dug tunnel). Open
                // sky at a shoreline is neither, so lakes do not weep thin
                // films across every beach they touch. The emitted flow is
                // level 7, which fades to nothing at level 2, so the lake can
                // never creep outward the way a true source would.
                emitted |= emitTerrainWaterOutlet(x, y, z, x - 1, z);
                emitted |= emitTerrainWaterOutlet(x, y, z, x + 1, z);
                emitted |= emitTerrainWaterOutlet(x, y, z, x, z - 1);
                emitted |= emitTerrainWaterOutlet(x, y, z, x, z + 1);
                if (emitted) {
                    // Reservoir cells are persistent sources while breached.
                    // Requeue only a cell that actually emitted, so sealed
                    // lakes do not consume the simulation budget.
                    enqueueWaterUpdate(x, y, z);
                }
                continue;
            }
            /*
             * Flow water (2..7) was pushed out by a source. Nothing but that
             * source supports it, so once nothing stronger feeds it, it shrinks.
             */
            if (level < 8 && !hasStrongerSupport(x, y, z, level)) {
                BLOCK_LOCK.writeLock().lock();
                try {
                    // Steps from 2 straight to dry: level 1 belongs to terrain
                    // water, and drained flow water must not masquerade as it.
                    source.setWaterLevel(lx, y, lz, level == 2 ? 0 : level - 1);
                } finally {
                    BLOCK_LOCK.writeLock().unlock();
                }
                enqueueWaterUpdate(x, y, z);
                enqueueWaterUpdate(x - 1, y, z);
                enqueueWaterUpdate(x + 1, y, z);
                enqueueWaterUpdate(x, y, z - 1);
                enqueueWaterUpdate(x, y, z + 1);
                continue;
            }

            /*
             * A flowing column must remain vertical until it reaches terrain.
             * In particular, a cell whose destination is another water cell is
             * still part of that column; it must not fan out just because its
             * own downward write is a no-op.
             */
            if (waterLevelAtWorld(x, y - 1, z) > 0) {
                continue;
            }

            int belowType = blockTypeAtWorld(x, y - 1, z);
            if (belowType < 0) {
                // An unloaded neighbour is not known terrain. Let chunk loading
                // requeue this cell instead of creating a false shelf.
                continue;
            }

            boolean flowedDown = false;
            /*
             * Falling water recharges to a full flow height. Whatever crested
             * a lip lands as a full-height column, so a plunge pool beneath a
             * drop fills flush with the lip instead of mirroring however thin
             * the stream had faded upstream. Sources still cap at flow level.
             */
            int downwardLevel = 7;
            for (int drop = 1; drop <= MAX_WATER_DROP_DISTANCE && y - drop >= 0; drop++) {
                if (!spreadWater(x, y - drop, z, downwardLevel)) {
                    break;
                }
                flowedDown = true;
            }
            /*
             * Lateral spreading stops at level 2: level 1 belongs to water the
             * terrain holds, and flow water has to stay reclaimable.
             */
            if (!flowedDown && level > 2 && !Block.isWaterReplaceable(belowType)) {
                spreadWater(x - 1, y, z, level - 1);
                spreadWater(x + 1, y, z, level - 1);
                spreadWater(x, y, z - 1, level - 1);
                spreadWater(x, y, z + 1, level - 1);
            }

        }
        return Math.min(processed, budget);
    }

    /** Evaluates up to the configured active-world water budget. */
    public static int processGlobalWaterUpdates() {
        return processGlobalWaterUpdates(MAX_GLOBAL_WATER_UPDATES);
    }

    /**
     * Evaluates a bounded slice of every water voxel in the active chunks. The
     * snapshot and cursor persist between calls, so a large active world does
     * not restart its scan or create a frame-sized hitch on every water tick.
     * Water created during a pass enters the next snapshot, preserving the
     * deterministic down-and-out ordering.
     */
    public static int processGlobalWaterUpdates(int budget) {
        if (budget <= 0 || globalWaterUpdateInProgress) {
            return 0;
        }
        globalWaterUpdateInProgress = true;
        try {
            return processGlobalWaterUpdatesSlice(budget);
        } finally {
            globalWaterUpdateInProgress = false;
        }
    }

    private static int processGlobalWaterUpdatesSlice(int budget) {
        processWaterDrains(MAX_WATER_DRAINS);
        waterEpoch++;

        if (globalWaterCellCursor >= globalWaterCellCount) {
            captureGlobalWaterSnapshot();
        }

        int processed = 0;
        while (processed < budget && globalWaterCellCursor < globalWaterCellCount) {
            long key = globalWaterCells[globalWaterCellCursor++];
            int x = waterX(key), y = waterY(key), z = waterZ(key);
            int level = waterLevelAtWorld(x, y, z);
            if (level > 0) {
                processGlobalWaterCell(x, y, z, level);
            }
            processed++;
        }
        // Queue notifications are still useful to the edit-path tests and
        // boundary replay, but the active-world pass is state-driven.
        waterQueue.clear();
        return processed;
    }

    private static void captureGlobalWaterSnapshot() {
        ArrayList<WorldChunk> activeChunks;
        synchronized (chunks) {
            activeChunks = new ArrayList<>(chunks);
        }
        globalWaterCellCount = 0;
        globalWaterCellCursor = 0;
        BLOCK_LOCK.readLock().lock();
        try {
            for (WorldChunk chunk : activeChunks) {
                if (chunk == null || !chunk.isGenerated || chunk.waterLevels == null) {
                    continue;
                }
                for (int cellIndex = chunk.nextWaterCellIndex(0);
                        cellIndex >= 0;
                        cellIndex = chunk.nextWaterCellIndex(cellIndex + 1)) {
                    int x = cellIndex / (WorldChunk.sizeY * WorldChunk.sizeZ);
                    int remainder = cellIndex % (WorldChunk.sizeY * WorldChunk.sizeZ);
                    int y = remainder / WorldChunk.sizeZ;
                    int z = remainder % WorldChunk.sizeZ;
                    if (chunk.waterLevel(x, y, z) > 0) {
                        appendGlobalWaterCell(
                                chunk.worldPosX + x, y, chunk.worldPosY + z);
                    }
                }
            }
        } finally {
            BLOCK_LOCK.readLock().unlock();
        }
    }

    private static void appendGlobalWaterCell(int x, int y, int z) {
        if (globalWaterCellCount == globalWaterCells.length) {
            globalWaterCells = Arrays.copyOf(globalWaterCells, globalWaterCells.length * 2);
        }
        globalWaterCells[globalWaterCellCount++] = waterKey(x, y, z);
    }

    private static void processGlobalWaterCell(int x, int y, int z, int level) {
        int columnBottomY = y;
        boolean flowedDown = false;
        int downwardLevel = level == 1 || level == 8 ? 7 : level;

        for (int scanY = y - 1; scanY >= 0; scanY--) {
            if (waterLevelAtWorld(x, scanY, z) > 0) {
                columnBottomY = scanY;
                continue;
            }
            int type = blockTypeAtWorld(x, scanY, z);
            if (type < 0) {
                return;
            }
            if (!Block.isWaterReplaceable(type)) {
                break;
            }
            if (spreadWater(x, scanY, z, downwardLevel, false)) {
                flowedDown = true;
            }
            columnBottomY = scanY;
        }

        int supportType = blockTypeAtWorld(x, columnBottomY - 1, z);
        boolean onTerrain = isTerrainBlock(supportType);
        if (!onTerrain) {
            return;
        }
        if (level >= 2 && level < 8 && !flowedDown
                && !hasStrongerSupport(x, y, z, level)) {
            WorldChunk source = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                    Math.floorDiv(z, WorldChunk.sizeZ));
            if (source != null) {
                int lx = Math.floorMod(x, WorldChunk.sizeX);
                int lz = Math.floorMod(z, WorldChunk.sizeZ);
                BLOCK_LOCK.writeLock().lock();
                try {
                    source.setWaterLevel(lx, y, lz, level == 2 ? 0 : level - 1);
                } finally {
                    BLOCK_LOCK.writeLock().unlock();
                }
            }
            return;
        }

        int lateralLevel;
        if (level == 1 || level == 8) {
            lateralLevel = flowedDown ? 6 : 7;
        } else {
            lateralLevel = level - 1;
        }
        if (lateralLevel < 2) {
            return;
        }
        if (lateralLevel <= 4) {
            // Weak head: ambient spread gets one turn per cadence window.
            // Claiming the turn is not granting it: the front oozes.
            WorldChunk cadence = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                    Math.floorDiv(z, WorldChunk.sizeZ));
            if (cadence == null) {
                return;
            }
            int clx = Math.floorMod(x, WorldChunk.sizeX);
            int clz = Math.floorMod(z, WorldChunk.sizeZ);
            if (!cadence.lateralFlowTurn(clx, y, clz, waterEpoch)) {
                return;
            }
        }
        spreadTerrainWater(columnBottomY, x - 1, z, lateralLevel);
        spreadTerrainWater(columnBottomY, x + 1, z, lateralLevel);
        spreadTerrainWater(columnBottomY, x, z - 1, lateralLevel);
        spreadTerrainWater(columnBottomY, x, z + 1, lateralLevel);
    }

    private static void spreadTerrainWater(int y, int targetX, int targetZ, int level) {
        if (!Block.isWaterReplaceable(blockTypeAtWorld(targetX, y, targetZ))) {
            return;
        }
        if (!isTerrainBlock(blockTypeAtWorld(targetX, y - 1, targetZ))) {
            return;
        }
        spreadWater(targetX, y, targetZ, level, false);
    }

    private static boolean isTerrainBlock(int type) {
        return type >= 0 && type != Block.WATER && !Block.isWaterReplaceable(type);
    }

    /**
     * True when the adjacent cell is open, air that water poured into it would
     * not simply coat and dry in: open space below it gives the water
     * somewhere to fall, and a ceiling within reach marks an enclosed void
     * rather than a beach. Either case is a breach worth draining through;
     * open sky is not.
     */
    private static boolean emitTerrainWaterOutlet(int x, int y, int z,
                                                  int outletX, int outletZ) {
        if (!Block.isWaterReplaceable(blockTypeAtWorld(outletX, y, outletZ))) {
            return false;
        }
        boolean escapesDown = Block.isWaterReplaceable(blockTypeAtWorld(outletX, y - 1, outletZ))
                || Block.isWaterReplaceable(blockTypeAtWorld(outletX, y - 2, outletZ));
        if (!escapesDown && !hasCeilingAbove(outletX, y, outletZ)) {
            return false;
        }
        return spreadWater(outletX, y, outletZ, 7);
    }

    /** Whether something solid roofs the outlet cell within reach. */
    private static boolean hasCeilingAbove(int x, int y, int z) {
        for (int up = 1; up <= MAX_WATER_DROP_DISTANCE; up++) {
            int type = blockTypeAtWorld(x, y + up, z);
            if (type < 0) {
                return false;   // unloaded sky: settle when the chunk loads
            }
            if (!Block.isWaterReplaceable(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStrongerSupport(int x, int y, int z, int level) {
        int[][] neighbors = {{x - 1, y, z}, {x + 1, y, z},
            {x, y, z - 1}, {x, y, z + 1}, {x, y + 1, z}};
        for (int[] neighbor : neighbors) {
            int neighborLevel = waterLevelAtWorld(neighbor[0], neighbor[1], neighbor[2]);
            if (neighborLevel == 1
                    || neighborLevel > level
                    || (neighbor[1] == y + 1 && neighborLevel >= 2)) {
                // Living water anywhere overhead feeds this cell, even a thin
                // trickle: that is what keeps a recharged waterfall column
                // standing over a lip it spilled from. Drying stays assured,
                // because feeders lose their own support first and the empty
                // space marches down through the column.
                return true;
            }
        }
        return false;
    }

    private static int waterLevelAtWorld(int x, int y, int z) {
        if (y < 0 || y >= WorldChunk.sizeY) {
            return 0;
        }
        WorldChunk chunk = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                Math.floorDiv(z, WorldChunk.sizeZ));
        if (chunk == null || !chunk.isGenerated || chunk.waterLevels == null) {
            return 0;
        }
        return chunk.waterLevel(Math.floorMod(x, WorldChunk.sizeX), y,
                Math.floorMod(z, WorldChunk.sizeZ));
    }

    /** Returns -1 when the voxel is outside the loaded/generated world. */
    private static int blockTypeAtWorld(int x, int y, int z) {
        if (y < 0 || y >= WorldChunk.sizeY) {
            return Block.STONE;
        }
        WorldChunk chunk = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                Math.floorDiv(z, WorldChunk.sizeZ));
        if (chunk == null || !chunk.isGenerated) {
            return -1;
        }
        return chunk.getBlock(Math.floorMod(x, WorldChunk.sizeX), y,
                Math.floorMod(z, WorldChunk.sizeZ));
    }

    private static boolean spreadWater(int x, int y, int z, int level) {
        return spreadWater(x, y, z, level, true);
    }

    private static boolean spreadWater(int x, int y, int z, int level,
                                       boolean enqueueNeighbors) {
        if (y < 0 || y >= WorldChunk.sizeY) return false;
        WorldChunk target = getChunk(Math.floorDiv(x, WorldChunk.sizeX),
                Math.floorDiv(z, WorldChunk.sizeZ));
        if (target == null || !target.isGenerated) return false;
        int lx = Math.floorMod(x, WorldChunk.sizeX), lz = Math.floorMod(z, WorldChunk.sizeZ);
        int type = target.getBlock(lx, y, lz);
        if (type != Block.WATER && !Block.isWaterReplaceable(type)) return false;
        int old = target.waterLevel(lx, y, lz);
        if (old >= 8 || old >= level) return false;
        // A level-1 cell is an anchored reservoir member. Ordinary flow must
        // never launder it into disposable flow water, or a breach would
        // drain the lake by converting it cell by cell instead of spilling.
        if (old == 1) return false;
        BLOCK_LOCK.writeLock().lock();
        try {
            if (target.setWaterLevel(lx, y, lz, level)) {
                if (enqueueNeighbors) {
                    enqueueWaterUpdate(x, y, z);
                    enqueueWaterUpdate(x - 1, y, z);
                    enqueueWaterUpdate(x + 1, y, z);
                    enqueueWaterUpdate(x, y, z - 1);
                    enqueueWaterUpdate(x, y, z + 1);
                }
                return true;
            }
        } finally {
            BLOCK_LOCK.writeLock().unlock();
        }
        return false;
    }

    private void pickSelectedBlock() {
        if (!Game.FIND_SELECTED_BLOCK) {
            return;
        }
        BlockFinder.RayHit hit = BlockFinder.pickTargetedBlock();
        if (hit != null) {
            BlockFinder.setSelectedBlock(hit.x, hit.y, hit.z);
        }
        // Called even with nothing targeted, so a click at open sky clears the
        // request. It used to stay pending and fire at whatever the player
        // looked at next.
        handleSelectedBlock(hit);
    }

    /**
     * Applies any block break/place requested by {@link Input} since the last frame.
     * Input is captured on the main loop via GLFW and surfaced here as flags rather
     * than the old LWJGL 2 Mouse event queue.
     */
    private void handleSelectedBlock(BlockFinder.RayHit hit) {
        if (hit == null) {
            BREAK_BLOCK_REQUESTED = false;
            PLACE_BLOCK_REQUESTED = false;
            PICK_BLOCK_REQUESTED = false;
            return;
        }
        if (PICK_BLOCK_REQUESTED) {
            PICK_BLOCK_REQUESTED = false;
            int type = BlockFinder.blockTypeAt(hit.x, hit.y, hit.z);
            if (type != Block.AIR && type < Block.BLOCK_NAMES.length) {
                Game.SELECTED_BLOCK_TYPE = type;
                Game.consoleMsg("Picked " + Block.nameOf(type));
            }
        }
        if (PLACE_BLOCK_REQUESTED) {
            PLACE_BLOCK_REQUESTED = false;
            if (!wouldTrapPlayer(hit.placeX, hit.placeY, hit.placeZ)) {
                BlockFinder.setBlockType(hit.placeX, hit.placeY, hit.placeZ, Game.SELECTED_BLOCK_TYPE);
                Game.consoleMsg("Placed " + Block.nameOf(Game.SELECTED_BLOCK_TYPE)
                        + " at " + hit.placeX + "," + hit.placeY + "," + hit.placeZ);
            }
        }
        if (BREAK_BLOCK_REQUESTED) {
            BREAK_BLOCK_REQUESTED = false;
            BlockFinder.setBlockType(hit.x, hit.y, hit.z, 0);
            Game.consoleMsg("Broke a block at " + hit.x + "," + hit.y + "," + hit.z);
        }
    }

    /**
     * Rejects placements that would put a block inside the player's own box.
     */
    private static boolean wouldTrapPlayer(int worldX, int worldY, int worldZ) {
        Vector3d p = Game.GAME_CAMERA.position;
        double minX = p.x - 0.3, maxX = p.x + 0.3;
        double minY = p.y - 1.62, maxY = p.y + 0.18;
        double minZ = p.z - 0.3, maxZ = p.z + 0.3;
        return maxX > worldX && minX < worldX + 1
                && maxY > worldY && minY < worldY + 1
                && maxZ > worldZ && minZ < worldZ + 1;
    }

    private void serializeAndFreeInactiveChunks() {
        drainDestroyQueue();

        long now = System.nanoTime();
        if (now < nextSweepAtNanos || !SWEEPER_IS_SLEEPING || !World.WAKE_SWEEPER) {
            return;
        }
        nextSweepAtNanos = now + SWEEP_INTERVAL_NANOS;
        SWEEPER_IS_SLEEPING = false;
        World.WAKE_SWEEPER = false;

        int chunkRadius = effectiveRenderDistance();
        int currentChunkX = (int) Math.floor(camera.position.x / WorldChunk.sizeX);
        int currentChunkY = (int) Math.floor(camera.position.z / WorldChunk.sizeZ);
        Runnable chunkSweeper =
                new WorldInactiveChunkSweeperThread(chunks, currentChunkX, currentChunkY, chunkRadius);
        try {
            maintenancePool.execute(chunkSweeper);
        } catch (RejectedExecutionException e) {
            SWEEPER_IS_SLEEPING = true;
            WAKE_SWEEPER = true;
        }
    }

    /**
     * GPU destruction remains on the render thread, but it drains every frame
     * independently of sweep scheduling. The token bucket bounds GL work while
     * preventing a maintenance scan from holding up already-condemned chunks.
     */
    private void drainDestroyQueue() {
        int sweptChunks = 0;
        synchronized (World.destroyChunks) {
            int backlog = destroyChunks.size();
            refillTeardownTokens();
            int toSweep = Math.min(backlog, (int) teardownTokens);
            teardownTokens -= toSweep;
            for (int i = 0; i < toSweep; i++) {
                WorldChunk deadChunk = destroyChunks.remove(destroyChunks.size() - 1);
                if (deadChunk == null) {
                    continue;
                }
                deadChunk.queuedForDestroy = false;
                if (deadChunk.queuedForDestroyAtNanos != 0) {
                    PipeTimer.age(PipeTimer.TEAR,
                            System.nanoTime() - deadChunk.queuedForDestroyAtNanos);
                    deadChunk.queuedForDestroyAtNanos = 0;
                }
                deadChunk.serialize();
                deadChunk.deleteVBO();
                synchronized (World.chunks) {
                    int at = chunks.indexOf(deadChunk);
                    if (at >= 0) {
                        int last = chunks.size() - 1;
                        chunks.set(at, chunks.get(last));
                        chunks.remove(last);
                    }
                }
                unregisterChunk(deadChunk);
                sweptChunks++;
            }
        }
        Game.STAT_SWEPT_CHUNKS += sweptChunks;
    }

    /**
     * Rings beyond the nominal draw distance that are still traversed so a
     * chunk's opacity can ramp to zero *while it is on screen*, instead of
     * being culled at full opacity and blinking out. The sweep ring sits past
     * this (see WorldInactiveChunkSweeperThread), so the destroy fade only
     * ever starts on chunks the player can no longer see.
     */
    private static final int FADE_OUT_RINGS = 3;
    /** Ground around the player publishes immediately even on a cold start. */
    private static final int SUPPORT_NEAR_EXEMPT_RINGS = 4;
    /**
     * Chunks actually issued to the GPU this frame. Coverage, not queue state,
     * is the number that corresponds to what the player sees -- if this falls
     * far short of the rings on screen, something is withholding terrain.
     */
    public static int drawnChunksThisFrame;
    /** Rim radius (nominal draw distance + fade band) of the current frame. */
    private int renderRimRadius;

    //Render any WorldChunks that happen to be within the chunkRadius of the current camera position
    public void render() {
        if (Game.DEBUG_DRAW_CAMERA_RAY && !pickerRay.isEmpty()) {
            drawPickerRay();
        }

        int chunkRadius = effectiveRenderDistance(); //Chunks around the player to load and draw this frame
        int currentChunkX = (int) Math.floor(camera.position.x / WorldChunk.sizeX);
        int currentChunkY = (int) Math.floor(camera.position.z / WorldChunk.sizeZ);
        // Loading, budgeting and sweeping still key off chunkRadius; the extra
        // rings exist purely to draw already-existing chunks as they fade.
        renderRimRadius = chunkRadius + FADE_OUT_RINGS;
        
        World.CURRENT_BOUND_XL = currentChunkX - chunkRadius;         //Lower X
        World.CURRENT_BOUND_XU = currentChunkX + chunkRadius;  //Upper X
        World.CURRENT_BOUND_YL = currentChunkY - chunkRadius; // Lower Y
        World.CURRENT_BOUND_YU = currentChunkY + chunkRadius;  //Upper Y
        recenterQueuedTasks(currentChunkX, currentChunkY);
        
        int rimXL = currentChunkX - renderRimRadius;
        int rimXU = currentChunkX + renderRimRadius;
        int rimYL = currentChunkY - renderRimRadius;
        int rimYU = currentChunkY + renderRimRadius;

        int midX = currentChunkX;
        int midY = currentChunkY;
        // Reported for diagnostics only: the widest contiguous ready ring. What
        // gets DRAWING is decided per chunk by camera-side support below, not
        // by a global curtain -- a single lagging cell behind a world-spanning
        // blindfold was exactly the flicker being chased.
        World.lastReadyFrontier = readyFrontierRadius(currentChunkX, currentChunkY, chunkRadius);
        World.lastPublicationRadius = Math.max(SUPPORT_NEAR_EXEMPT_RINGS,
                World.lastReadyFrontier + 1);
        heldForPublicationThisFrame = 0;
        newlyPublishedThisFrame = 0;
        drawnChunksThisFrame = 0;
        
        Renderer.beginChunkPass();
        
        for (int radius = 0; radius <= renderRimRadius; radius++) {
            int xRadiusLower = Math.max(midX - radius, rimXL);
            int yRadiusLower = Math.max(midY - radius, rimYL);
            int xRadiusUpper = Math.min(midX + radius, rimXU);
            int yRadiusUpper = Math.min(midY + radius, rimYU);
            
            if (radius == 0) {
                renderChunk(xRadiusLower, yRadiusLower, midX, midY, radius, chunkRadius);
                continue;
            }
            
            //do all x+
            for (int i = xRadiusLower; i < xRadiusUpper; i++) {
                renderChunk(i, yRadiusLower, midX, midY, radius, chunkRadius);
            }
            
            //do all y+
            for (int i = yRadiusLower; i < yRadiusUpper; i++) {
                renderChunk(xRadiusUpper, i, midX, midY, radius, chunkRadius);
            }
            
            //do all x-
            for (int i = xRadiusUpper; i > xRadiusLower; i--) {
                renderChunk(i, yRadiusUpper, midX, midY, radius, chunkRadius);
            }
            
            //do all y-
            for (int i = yRadiusUpper; i > yRadiusLower; i--) {
                renderChunk(xRadiusLower, i, midX, midY, radius, chunkRadius);
            }
        }
        Game.STAT_BUILT_CHUNKS += BUILT_CHUNKS;
        
        Renderer.endChunkPass();
    }

    /** Debug visualisation of the block-picking ray, drawn with the line shader. */
    private void drawPickerRay() {
        int count = pickerRay.size();
        float[] points = new float[count * 3];
        for (int i = 0; i < count; i++) {
            Vector3d r = pickerRay.get(i);
            points[i * 3] = (float) ((int) r.x - Game.GAME_CAMERA.position.x);
            points[i * 3 + 1] = (float) (int) r.y;
            points[i * 3 + 2] = (float) ((int) r.z - Game.GAME_CAMERA.position.z);
        }
        Renderer.drawDebugGeometry(GL_POINTS, points, count, PICKER_RAY_MODEL, 1f, 0f, 1f, 1f);
    }

    private static final org.joml.Matrix4f PICKER_RAY_MODEL = new org.joml.Matrix4f();


    private int readyFrontierRadius(int centerX, int centerY, int radius) {
        int frontier = -1;
        for (int ring = 0; ring <= radius; ring++) {
            int lowerX = centerX - ring;
            int upperX = centerX + ring;
            int lowerY = centerY - ring;
            int upperY = centerY + ring;
            if (ring == 0) {
                if (!chunkReadyForDraw(lowerX, lowerY)) {
                    break;
                }
                frontier = 0;
                continue;
            }
            int incomplete = 0;
            for (int x = lowerX; x <= upperX; x++) {
                if (!chunkReadyForDraw(x, lowerY)) {
                    incomplete++;
                }
                if (!chunkReadyForDraw(x, upperY)) {
                    incomplete++;
                }
            }
            for (int z = lowerY + 1; z < upperY; z++) {
                if (!chunkReadyForDraw(lowerX, z)) {
                    incomplete++;
                }
                if (!chunkReadyForDraw(upperX, z)) {
                    incomplete++;
                }
            }
            if (incomplete > ringStragglerTolerance(ring)) {
                break;
            }
            frontier = ring;
        }
        return frontier;
    }

    /**
     * Publication is strict because it never hides a chunk that was already
     * shown. A straggler may delay new outer terrain, but cannot blank the
     * existing horizon; allowing holes here is what created scattered islands.
     */
    private static int ringStragglerTolerance(int ring) {
        return 0;
    }

    private boolean chunkReadyForDraw(int x, int z) {
        WorldChunk chunk = World.getChunk(x, z);
        if (chunk == null) {
            return false;
        }
        // A pending MESH is not an outage: an existing VBO stays valid and
        // fully drawable until the refresh uploads underneath (same principle
        // as the refresh comment at the draw loop). Penalising pending meshes
        // made support flicker false/true with every promotion batch, and the
        // chunks behind held/drew in strobes -- mid-fade cutoffs, visible
        // flashing at the horizon even with a still camera.
        if (chunk.isGenerating) {
            return false;
        }
        if (chunk.vboVertexHandle != 0) {
            return true;
        }
        if (chunk.isBuilding) {
            return false;
        }
        return chunk.isBuilt && !chunk.hasPendingMesh();
    }

    private void renderChunk(int i, int j, int currentChunkX, int currentChunkY,
                             int innerRadius, int outerRadius) {
        WorldChunk thisChunk = World.getChunk(i, j);
        if (thisChunk == null && innerRadius <= outerRadius && canLoadChunk(innerRadius)
                && World.GEN_CHUNKS < World.MAX_CHUNKS_TO_GEN) {
            thisChunk = new WorldChunk(i, j);
            synchronized (World.chunks) {
                chunks.add(thisChunk);
            }
            registerChunk(thisChunk);
            //continue;
        }
        if (thisChunk != null) {
            // A refresh builds a replacement mesh while the existing VBO remains
            // valid. Hiding the chunk for isRefreshing creates visible holes as
            // lighting invalidates neighbouring cave meshes during startup.
            // Initial generation/builds still have no drawable VBO and wait.
            if (thisChunk.isGenerating || (thisChunk.isBuilding && !thisChunk.isReady())) {
                ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.M_NOTREADY);
                return;
            }

            if (!thisChunk.isGenerated && innerRadius <= outerRadius
                    && canLoadChunk(innerRadius) && GEN_CHUNKS < World.MAX_CHUNKS_TO_GEN
                    && !inChunkSubmitBackoff()) {
                // Reserve before enqueueing; otherwise a busy executor leaves
                // the chunk looking idle and every frame submits another job.
                thisChunk.isGenerating = true;
                thisChunk.isGeneratingSince = System.nanoTime();
                Runnable chunkBuilder = new WorldChunkLoadThread(thisChunk);
                try {
                    submitChunkTask(chunkBuilder, thisChunk, TASK_GENERATION);
                    GEN_CHUNKS++;
                } catch (RejectedExecutionException e) {
                    thisChunk.isGenerating = false;
                    enterChunkSubmitBackoff();
                }
                ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.M_NOTREADY);
                return;
            }
            if (thisChunk.isGenerated && !thisChunk.isBuilt && canLoadChunk(innerRadius)
                    && BUILT_CHUNKS < buildBudgetFor(innerRadius)
                    && !inChunkSubmitBackoff()
                    // Core exemption: the six rings around the player build no
                    // matter what the pending pile looks like -- freezing mesh
                    // submits there is how the worldstream strands its own
                    // neighbourhood. Far chunks wait behind the bar.
                    && (innerRadius <= 6
                            || WorldChunk.pendingMeshBytesTotal() < MAX_PENDING_MESH_BYTES - PENDING_ADMISSION_MARGIN)) {

                //This chunk is not building and not built, so lets build it..

                //Don't build meshes for chunks that are on the edge of the built list 
                //We can't know if the neighboring blocks are exposed until the neighbor is generated
                if (innerRadius < outerRadius - 1 && cardinalNeighborsGenerated(i, j)) {
                    thisChunk.isRefreshing = true;
                    thisChunk.isBuilding = true;
                    thisChunk.isBuildingSince = System.nanoTime();
                    Runnable chunkBufferBuilder = new WorldChunkBufferBuilderThread(thisChunk);
                    try {
                        submitChunkTask(chunkBufferBuilder, thisChunk);
                        BUILT_CHUNKS++;
                    } catch (RejectedExecutionException e) {
                        thisChunk.isBuilding = false;
                        thisChunk.isRefreshing = false;
                        enterChunkSubmitBackoff();
                    }
                }
                ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.M_NOTREADY);
                return;
            }
            if (thisChunk.isZombie) {
                synchronized (destroyChunks) {
                    destroyChunks.remove(thisChunk);
                }
                thisChunk.isZombie = false;
                // The player walked back into range before this chunk was
                // actually swept and freed -- resume its fade back in from
                // wherever the fade-out had reached, rather than either
                // leaving it fading toward invisible or popping straight to
                // full opacity.
                thisChunk.cancelDestroyFade();
            }
            //If the chunk is done (ready to render) and is immediately within the proximity of the current chunk or is otherwise within the frustum, render
            if (thisChunk.isReady()) {
                // Uploads are promoted regardless of whether this chunk will
                // be drawn this frame: withheld geometry must still become
                // drawable, or a gap can never heal.
                if (thisChunk.hasPendingMesh() && VBO_CHUNKS < World.MAX_CHUNKS_TO_VBO
                        && (vboUploadedThisFrame < vboUploadFloor
                                || System.nanoTime() < vboUploadDeadlineNanos)) {
                    long bornNano = thisChunk.meshReadyAtNanos;
                    if (bornNano > 0) {
                        PipeTimer.age(PipeTimer.PEND, System.nanoTime() - bornNano);
                    }
                    long upTicket = PipeTimer.begin();
                    thisChunk.uploadPendingMesh();
                    long spend = System.nanoTime() - upTicket;
                    PipeTimer.end(PipeTimer.UPLD, upTicket);
                    frameVboSpendNanos += spend;
                    VBO_CHUNKS++;
                    vboUploadedThisFrame++;
                }
                // Never hide terrain that was already published: turns must
                // preserve the existing world. New terrain is licensed only
                // through the next ring beyond the strict ready frontier, so
                // publication forms one contiguous wave around the player.
                boolean alreadyPublished = thisChunk.meshReadyAtNanos >= 0;
                int publicationRadius = Math.max(SUPPORT_NEAR_EXEMPT_RINGS,
                        lastReadyFrontier + 1);
                boolean supportedHere = alreadyPublished || innerRadius <= publicationRadius;
                if (!supportedHere) {
                    heldForPublicationThisFrame++;
                    ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.C_HELD,
                            currentChunkX, currentChunkY);
                } else {
                    // Frustum culling must only skip DRAWING. Gating mesh
                    // generation on visibility leaves permanent holes, because
                    // an off-screen chunk would never become drawable.
                    boolean cullable = Game.OPT_CULL_CHUNKS && innerRadius > 1;
                    if (cullable && !thisChunk.isVisible()) {
                        ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.F_CULLED,
                                currentChunkX, currentChunkY);
                    } else {
                        // Stamped before computing this frame's alpha, not after
                        // render() (which is where buildVBO() actually runs).
                        if (!alreadyPublished) {
                            newlyPublishedThisFrame++;
                        }
                        thisChunk.ensureFadeInStarted();

                        double dx = (i + 0.5) - (camera.position.x / WorldChunk.sizeX);
                        double dz = (j + 0.5) - (camera.position.z / WorldChunk.sizeZ);
                        float chebyshevDist = (float) Math.max(Math.abs(dx), Math.abs(dz));
                        float fadeMargin = Math.max(2.0f, renderRimRadius * Game.OPT_CHUNK_EDGE_FADE_FRACTION);
                        float edgeFade = Math.max(0.0f, Math.min(1.0f,
                                (renderRimRadius - chebyshevDist) / fadeMargin));
                        thisChunk.renderAlpha = edgeFade * thisChunk.lifecycleFadeAlpha(innerRadius);
                        drawnChunksThisFrame++;
                        // Upload throttling must never throttle drawing an
                        // existing GPU mesh.
                        thisChunk.render();
                        ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.D_DRAWN,
                                currentChunkX, currentChunkY);
                    }
                }
                thisChunk.selectedBlock = null;
            } else {
                ChunkVisibilityWatch.observe(thisChunk, i, j, ChunkVisibilityWatch.M_NOTREADY);
            }
        }
        ChunkVisibilityWatch.flushThrottled(System.nanoTime());
    }

    public static boolean allNeighborsAreGenerated(WorldChunk chunk) {
        WorldChunk[] neighbors = new WorldChunk[4];
        boolean[] generated = new boolean[]{false, false, false, false};

        if (chunk.posX > World.CURRENT_BOUND_XL) {
            neighbors[1] = World.getChunk(chunk.posX - 1, chunk.posY);
            if (neighbors[1] != null) {
                generated[1] = neighbors[1].isGenerated;
            }
        } else {
            generated[1] = true;
        }
        if (chunk.posY > World.CURRENT_BOUND_YL) {
            neighbors[3] = World.getChunk(chunk.posX, chunk.posY - 1);
            if (neighbors[3] != null) {
                generated[3] = neighbors[3].isGenerated;
            }
        } else {
            generated[3] = true;

        }
        if (chunk.posX < World.CURRENT_BOUND_XU) {
            neighbors[0] = World.getChunk(chunk.posX + 1, chunk.posY);
            if (neighbors[0] != null) {
                generated[0] = neighbors[0].isGenerated;
            }
        } else {
            generated[0] = true;
        }

        if (chunk.posY < World.CURRENT_BOUND_YU) {
            neighbors[2] = World.getChunk(chunk.posX, chunk.posY + 1);
            if (neighbors[2] != null) {
                generated[2] = neighbors[2].isGenerated;
            }
        } else {
            generated[2] = true;
        }

        return (generated[0] && generated[1] && generated[2] && generated[3]);
    }

    /**
     * Solidity test for movement. Water is passable so lakes can be waded into.
     */
    public static boolean isSolidForCollision(int worldX, int worldY, int worldZ) {
        if (worldY < 0) {
            return true;
        }
        if (worldY >= WorldChunk.sizeY) {
            return false;
        }
        int chunkX = Math.floorDiv(worldX, WorldChunk.sizeX);
        int chunkZ = Math.floorDiv(worldZ, WorldChunk.sizeZ);
        WorldChunk chunk = World.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.isGenerated || chunk.blocks == null) {
            return false;   // never trap the player inside unloaded terrain
        }
        int type = chunk.getBlock(Math.floorMod(worldX, WorldChunk.sizeX), worldY,
                Math.floorMod(worldZ, WorldChunk.sizeZ));
        return Block.isCollidable(type);
    }

    /**
     * Solidity by absolute world coordinates, used for ambient occlusion across
     * chunk borders. Unloaded chunks count as solid so seams do not flash bright.
     */
    public static boolean isSolidGlobal(int worldX, int y, int worldZ) {
        if (y < 0) {
            return true;
        }
        if (y >= WorldChunk.sizeY) {
            return false;
        }
        int chunkX = Math.floorDiv(worldX, WorldChunk.sizeX);
        int chunkZ = Math.floorDiv(worldZ, WorldChunk.sizeZ);
        WorldChunk chunk = World.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.isGenerated || chunk.blocks == null) {
            return true;
        }
        int lx = Math.floorMod(worldX, WorldChunk.sizeX);
        int lz = Math.floorMod(worldZ, WorldChunk.sizeZ);
        return !Block.isTransparent(chunk.getBlock(lx, y, lz));
    }

    /**
     * Sky light by absolute world coordinates, for light bleeding across chunk
     * borders. Only a published light field can seed a neighbour; treating
     * missing/unlit terrain as daylight injects phantom light into sealed caves
     * which is repeatedly removed as neighbouring chunks finish generation.
     */
    public static int skyLightGlobal(int worldX, int y, int worldZ) {
        if (y < 0) {
            return 0;
        }
        if (y >= WorldChunk.sizeY) {
            return WorldChunk.MAX_LIGHT;
        }
        WorldChunk chunk = World.getChunk(
                Math.floorDiv(worldX, WorldChunk.sizeX),
                Math.floorDiv(worldZ, WorldChunk.sizeZ));
        if (chunk == null || !chunk.isGenerated) {
            return 0;
        }
        int level = chunk.localLight(
                Math.floorMod(worldX, WorldChunk.sizeX), y,
                Math.floorMod(worldZ, WorldChunk.sizeZ));
        return Math.max(0, level);
    }

    //Looks at the neigher of a block on the edge of a chunk
    //Direction - 1:up, 2:down, 3:left, 4:right
    public static int chunkNeighbor(int direction, int x, int y, int z, int chunkX, int chunkY) {
        WorldChunk chunk = null;
        int block = 1;
        switch (direction) {
            case 1: //Up  
                //TODO: Block until the chunk is done
                if (chunkX < sizeX - 1 && chunkY < sizeY) {
                    chunk = World.getChunk(chunkX + 1, chunkY);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.getBlock(0, y, z);
                    }
                }
                break;
            case 2: //Down
                //TODO: Block until the chunk is done
                if (chunkX > 0 && chunkY < sizeY) {
                    chunk = World.getChunk(chunkX - 1, chunkY);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.getBlock(WorldChunk.sizeX - 1, y, z);
                    }
                }
                break;
            case 3: //Left
                //TODO: Block until the chunk is done
                if (chunkY > 0 && chunkX < sizeX) {
                    chunk = World.getChunk(chunkX, chunkY - 1);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.getBlock(x, y, WorldChunk.sizeZ - 1);
                    }
                }
                break;
            case 4: //Right
                //TODO: Block until the chunk is done
                if (chunkY < sizeY - 1 && chunkX < sizeX) {
                    chunk = World.getChunk(chunkX, chunkY + 1);
                    if (chunk != null && chunk.isGenerated) {
                        return chunk.getBlock(x, y, 0);
                    }
                }
                break;
        }
        return block;  //A null block - empty space
    }

    public static WorldChunk chunkNeighbor(int direction, WorldChunk chunk) {
        switch (direction) {
            case 1: //Up  
                return World.getChunk(chunk.posX + 1, chunk.posY);
            case 2: //Down
                return World.getChunk(chunk.posX - 1, chunk.posY);
            case 3: //Left
                return World.getChunk(chunk.posX, chunk.posY - 1);
            case 4: //Right
                return World.getChunk(chunk.posX, chunk.posY + 1);
        }
        return null;
    }

    public static long getSeed() {
        return WORLD_SEED;
    }
}
