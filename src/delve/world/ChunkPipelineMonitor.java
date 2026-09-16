package delve.world;

import delve.core.Game;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Periodic census of the chunk streaming pipeline, printed to stdout.
 *
 * Fast flight at maximum draw distance was reported to leave random chunk
 * holes that persisted across world reloads. Whether that is throughput,
 * queue behaviour, or a lock wedge cannot be settled from screenshots, so the
 * census records the evidence: how many chunks sit in each lifecycle state
 * near and far, the queue depth and completion rate, the block-lock state,
 * and -- when nothing has finished for a while, or the VM reports a deadlock
 * -- the worker thread stacks.
 *
 * It also sweeps chunk flags that have been set implausibly long. A chunk
 * with isGenerating stuck true is skipped by World.renderChunk() forever,
 * which is exactly the "hole I cannot stand on" symptom; clearing the flag
 * lets the chunk be re-submitted even if the original worker never returns.
 */
public final class ChunkPipelineMonitor {

    private static final long TICK_NANOS = 2_000_000_000L;
    /** No task finished for this long while work was pending: dump stacks. */
    private static final long STALL_WARN_NANOS = 3_000_000_000L;
    /** No generation/mesh task finished for this long with busy workers. */
    private static final long STUCK_CHUNK_TASK_NANOS = 8_000_000_000L;
    private static final long DUMP_COOLDOWN_NANOS = 20_000_000_000L;
    /** A flag held this long cannot be explained by any normal chunk work. */
    private static final long STUCK_FLAG_NANOS = 30_000_000_000L;
    /** Rings inspected cell-by-cell for absent chunks (whole-grid scan). */
    private static final int NEAR_FIELD_RING = 12;
    private static final int STATE_COUNT = 7;
    private static final String[] STATE_NAMES = {
            "wait-gen", "gen", "que-mesh", "mesh", "pend-vbo", "vbo", "empty"};

    /**
     * Latest census, published for the performance overlay whether or not
     * anything was worth printing about it.
     */
    public static volatile int snapshotResidentChunks;
    public static volatile int snapshotZombieChunks;
    public static volatile int snapshotPendingTasks;
    public static volatile int snapshotBusyWorkers;
    public static volatile long snapshotSubmitsPerSecond;
    public static volatile long snapshotCompletionsPerSecond;
    public static volatile int snapshotMissingNearField;
    public static volatile long snapshotTakenAtMillis;
    /** Chunks per lifecycle state within the near field of the last census. */
    public static volatile int[] snapshotNearFieldStates = new int[STATE_COUNT];

    public static String stateName(int index) {
        return STATE_NAMES[Math.max(0, Math.min(STATE_NAMES.length - 1, index))];
    }

    public static int stateCount() {
        return STATE_COUNT;
    }

    private static volatile long lastTaskEndAtNanos = System.nanoTime();
    /** Generation/mesh completions only; sweepers keep {@link #lastTaskEndAtNanos} warm. */
    private static volatile long lastChunkTaskEndAtNanos = System.nanoTime();
    private static long nextTickAtNanos;
    private static long lastDumpAtNanos = Long.MIN_VALUE;
    private static long prevSubmitted = -1;
    private static long prevCompleted = -1;

    private ChunkPipelineMonitor() {
    }

    static void markTaskEnd() {
        lastTaskEndAtNanos = System.nanoTime();
    }

    static void markChunkTaskEnd() {
        lastChunkTaskEndAtNanos = System.nanoTime();
        lastTaskEndAtNanos = System.nanoTime();
    }

    /** Called once per frame from the game update; reports at most every 2s. */
    public static void tick() {
        long now = System.nanoTime();
        if (now - nextTickAtNanos < 0) {
            return;
        }
        nextTickAtNanos = now + TICK_NANOS;
        PipeTimer.roll();

        // Surplus idle capacity finishes the next contiguous ring before the
        // player reaches it. It must never leapfrog rings.
        World.prewarmLookahead();

        long submitted = World.SUBMITTED_CHUNK_TASKS.get();
        long completed = World.COMPLETED_CHUNK_TASKS.get();
        int pending = World.pendingChunkTasks();
        int busy = World.busyChunkWorkers();
        long taskAge = now - lastTaskEndAtNanos;
        long chunkTaskAge = now - lastChunkTaskEndAtNanos;

        // A thread deadlock is news no matter how quiet everything looks.
        long[] deadlocked = findDeadlock();
        int swept = sweepStuckFlags(now);
        // Sitting in the settings menu or with time paused is not a stall: the
        // update path deliberately stops submitting work there.
        boolean parked = Game.MENU_OPEN || Game.DEV_MENU_OPEN || Game.TIME_PAUSED;
        boolean stalled = !parked && (taskAge >= STALL_WARN_NANOS
                && (pending > 0 || busy > 0)
                // Sweepers alone keep the generic heartbeat alive; if no real
                // chunk work has finished for a long stretch WHILE demand is
                // queued and workers are occupied, they are occupied by
                // something that never returns. (A fully loaded world sitting
                // still has no chunk work to finish, so it must not alarm.)
                || chunkTaskAge >= STUCK_CHUNK_TASK_NANOS && busy > 0 && pending > 0);

        int[] near = new int[STATE_COUNT];
        int[] far = new int[STATE_COUNT];
        int[] totals = census(near, far);
        int missingNear = countMissingNearField();

        long rate = prevSubmitted < 0 ? -1
                : (submitted - prevSubmitted) * 1000L / (TICK_NANOS / 1_000_000L);
        long done = prevCompleted < 0 ? -1
                : (completed - prevCompleted) * 1000L / (TICK_NANOS / 1_000_000L);
        rememberBaselines(submitted, completed);

        // Publish for the F6 overlay: it wants a live read of the pipeline even
        // when everything is healthy enough that nothing needs printing.
        snapshotResidentChunks = totals[1];
        snapshotZombieChunks = totals[2];
        snapshotPendingTasks = pending;
        snapshotBusyWorkers = busy;
        snapshotSubmitsPerSecond = rate;
        snapshotCompletionsPerSecond = done;
        snapshotMissingNearField = missingNear;
        snapshotNearFieldStates = near.clone();
        snapshotTakenAtMillis = System.currentTimeMillis();

        boolean quiet = pending == 0 && busy == 0 && taskAge < STALL_WARN_NANOS
                && World.lastReadyFrontier >= 0 && deadlocked == null
                && swept == 0 && missingNear == 0 && !Game.MEMORY_BOUND;
        if (quiet && !Game.STRESS_FLIGHT) {
            return;
        }

        ReentrantReadWriteLock lock = World.BLOCK_LOCK;
        int viewRadius = World.effectiveRenderDistance();
        StringBuilder line = new StringBuilder("[chunk-stream]");
        if (swept > 0) {
            line.append(" SWEPT_STUCK_FLAGS=").append(swept);
        }
        if (deadlocked != null) {
            line.append(" DEADLOCKED_THREADS=").append(deadlocked.length);
        }
        if (stalled) {
            line.append(" STALLED_MS=").append(Math.max(taskAge, chunkTaskAge) / 1_000_000L);
        }
        line.append(" queue=").append(pending)
                .append('/').append(World.MAX_QUEUED_CHUNK_TASKS)
                .append(" purged=").append(World.PURGED_CHUNK_TASKS.get())
                .append(" busy=").append(busy).append('/').append(World.totalChunkWorkers())
                .append(" rate=").append(rate).append('/').append(done).append(" per/s")
                .append(" frontier=").append(World.lastReadyFrontier)
                .append('/').append(viewRadius)
                .append(Game.OPT_DRAW_DISTANCE != viewRadius
                        ? " (want " + Game.OPT_DRAW_DISTANCE + ")" : "")
                .append(" drawn=").append(World.drawnChunksThisFrame)
                .append(" missingNear=").append(missingNear)
                .append(" meshHeap=").append(WorldChunk.pendingMeshBytesTotal() >>> 20).append("MB")
                .append(" mem=").append(Game.MEMORY_BOUND ? "LOW" : "ok")
                .append('(').append(Runtime.getRuntime().freeMemory() >>> 20)
                .append('/').append(Runtime.getRuntime().maxMemory() >>> 20).append("MB)")
                .append(" lock[r=").append(lock.getReadLockCount())
                .append(",w=").append(lock.isWriteLocked())
                .append(",wq=").append(lock.getQueueLength())
                .append(']');
        appendHistogram(line, "near", NEAR_FIELD_RING, near);
        appendHistogram(line, "far", totals[0], far);
        line.append(" chunks=").append(totals[1]).append(" zombies=").append(totals[2]);
        System.out.println(line);

        if ((stalled || deadlocked != null)
                && now - lastDumpAtNanos >= DUMP_COOLDOWN_NANOS) {
            lastDumpAtNanos = now;
            dumpThreads(deadlocked);
        }
    }

    private static void rememberBaselines(long submitted, long completed) {
        prevSubmitted = submitted;
        prevCompleted = completed;
    }

    /**
     * @return {maxRingSeen, chunkTotal, zombieTotal}
     */
    private static int[] census(int[] near, int[] far) {
        ArrayList<WorldChunk> snapshot;
        synchronized (World.chunks) {
            snapshot = new ArrayList<>(World.chunks);
        }
        int centerChunkX = (int) Math.floor(Game.GAME_CAMERA.position.x / WorldChunk.sizeX);
        int centerChunkZ = (int) Math.floor(Game.GAME_CAMERA.position.z / WorldChunk.sizeZ);
        int[] totals = new int[3];
        for (WorldChunk chunk : snapshot) {
            if (chunk == null) {
                continue;
            }
            totals[1]++;
            if (chunk.isZombie) {
                totals[2]++;
                continue;
            }
            int ring = Math.max(Math.abs(chunk.posX - centerChunkX),
                    Math.abs(chunk.posY - centerChunkZ));
            totals[0] = Math.max(totals[0], ring);
            int[] bucket = ring <= NEAR_FIELD_RING ? near : far;
            bucket[classify(chunk)]++;
        }
        return totals;
    }

    private static int classify(WorldChunk chunk) {
        if (chunk.vboVertexHandle != 0) {
            return 5;
        }
        if (chunk.isGenerating) {
            return 1;
        }
        if (!chunk.isGenerated) {
            return 0;
        }
        if (chunk.isBuilding) {
            return 3;
        }
        if (chunk.hasPendingMesh()) {
            return 4;
        }
        if (!chunk.isBuilt) {
            return 2;
        }
        // Built, nothing pending, no GPU mesh: fully-air or fully-enclosed
        // chunks. Legitimate, but worth seeing in the census.
        return 6;
    }

    private static void appendHistogram(StringBuilder line, String label,
                                        int maxRing, int[] counts) {
        line.append(' ').append(label).append('[');
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                line.append(' ');
            }
            line.append(STATE_NAMES[i]).append('=').append(counts[i]);
        }
        line.append("] (<=r").append(maxRing).append(')');
    }

    /**
     * Counts cells inside the near field with no chunk registered at all.
     * Those are holes the player can actually fall through, as opposed to
     * chunks that exist but are still meshing or fading in.
     */
    private static int countMissingNearField() {
        int radius = Math.min(World.effectiveRenderDistance(), NEAR_FIELD_RING);
        int centerX = (int) Math.floor(Game.GAME_CAMERA.position.x / WorldChunk.sizeX);
        int centerZ = (int) Math.floor(Game.GAME_CAMERA.position.z / WorldChunk.sizeZ);
        int missing = 0;
        for (int ring = 0; ring <= radius; ring++) {
            int loX = centerX - ring, hiX = centerX + ring;
            int loZ = centerZ - ring, hiZ = centerZ + ring;
            if (ring == 0) {
                if (World.getChunk(centerX, centerZ) == null) {
                    missing++;
                }
                continue;
            }
            for (int x = loX; x <= hiX; x++) {
                if (World.getChunk(x, loZ) == null) {
                    missing++;
                }
                if (World.getChunk(x, hiZ) == null) {
                    missing++;
                }
            }
            for (int z = loZ + 1; z < hiZ; z++) {
                if (World.getChunk(loX, z) == null) {
                    missing++;
                }
                if (World.getChunk(hiX, z) == null) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /**
     * Clears pipeline flags that have survived far longer than the work they
     * describe, returning the number of chunks revived. An abandoned
     * generation task (aborted worker, swallowed interrupt) otherwise pins a
     * chunk on the renderer's skip-list forever.
     */
    private static int sweepStuckFlags(long now) {
        ArrayList<WorldChunk> snapshot;
        synchronized (World.chunks) {
            snapshot = new ArrayList<>(World.chunks);
        }
        int swept = 0;
        for (WorldChunk chunk : snapshot) {
            if (chunk == null) {
                continue;
            }
            if (chunk.isGenerating && chunk.isGeneratingSince != 0
                    && now - chunk.isGeneratingSince >= STUCK_FLAG_NANOS) {
                chunk.isGenerating = false;
                chunk.isGeneratingSince = 0;
                swept++;
                System.out.println("[chunk-stream] unstuck generation at chunk "
                        + chunk.posX + "," + chunk.posY);
            }
            if (chunk.isBuilding && chunk.isBuildingSince != 0
                    && now - chunk.isBuildingSince >= STUCK_FLAG_NANOS) {
                chunk.isBuilding = false;
                chunk.isRefreshing = false;
                chunk.isBuildingSince = 0;
                swept++;
                System.out.println("[chunk-stream] unstuck mesh build at chunk "
                        + chunk.posX + "," + chunk.posY);
            }
        }
        return swept;
    }

    private static long[] findDeadlock() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads == null || !threads.isSynchronizerUsageSupported()) {
            return null;
        }
        try {
            long[] ids = threads.findDeadlockedThreads();
            return ids == null || ids.length == 0 ? null : ids;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Prints the stack of every thread involved in chunk streaming (pool
     * workers, the main/render thread) -- or, if the VM found a cycle, the
     * deadlocked set specifically. The census line supplies the counts; this
     * supplies the frames.
     */
    private static void dumpThreads(long[] deadlocked) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads == null) {
            return;
        }
        ThreadInfo[] all = threads.dumpAllThreads(false, false);
        if (deadlocked != null) {
            System.out.println("[chunk-stream] ==== DEADLOCK STACKS ====");
            for (ThreadInfo info : all) {
                if (info == null) {
                    continue;
                }
                for (long id : deadlocked) {
                    if (info.getThreadId() == id) {
                        printThread(info);
                    }
                }
            }
        } else {
            System.out.println("[chunk-stream] ==== PIPELINE STACKS (stalled) ====");
            for (ThreadInfo info : all) {
                if (info != null && interesting(info.getThreadName())) {
                    printThread(info);
                }
            }
        }
        System.out.println("[chunk-stream] ==== END STACKS ====");
    }

    private static void printThread(ThreadInfo info) {
        StringBuilder head = new StringBuilder("\"").append(info.getThreadName())
                .append("\" ").append(info.getThreadState());
        if (info.getLockName() != null) {
            head.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            head.append(" owned by ").append(info.getLockOwnerName());
        }
        System.out.println(head);
        StackTraceElement[] frames = info.getStackTrace();
        int limit = Math.min(frames.length, 18);
        for (int i = 0; i < limit; i++) {
            System.out.println("    at " + frames[i]);
        }
    }

    private static boolean interesting(String name) {
        return name.equals("main")
                || name.startsWith("pool-")
                || name.startsWith("Chunk")
                || name.toLowerCase().contains("sweeper");
    }
}
