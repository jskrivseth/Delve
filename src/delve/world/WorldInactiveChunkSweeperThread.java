/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package delve.world;

import org.joml.Vector2f;
import java.util.*;

import delve.core.Game;

/**
 *
 * @author Jesse
 */
public class WorldInactiveChunkSweeperThread implements Runnable {

    /**
     * Rings of clearance between the visible set (draw distance plus World's
     * fade band) and the sweep ring. Inside this clearance a chunk must never
     * be marked zombie, or its destroy fade competes with normal rendering and
     * the far horizon strobes as the camera crosses chunk boundaries.
     */
    private static final int SWEEP_MARGIN_RINGS = 6;
    /**
     * Condemnation pauses while this many chunks wait to be freed, and resumes
     * once the queue has drained (the sweep itself is the only producer, so no
     * separate low-water mark is needed: the check re-runs every sweep).
     */
    private static final int CONDEMN_BACKLOG_HIGH_WATER = 1024;

    ArrayList<WorldChunk> chunks;
    int x, y, radius;

    public WorldInactiveChunkSweeperThread(ArrayList<WorldChunk> chunks, int x, int y, int radius) {
        this.chunks = chunks;
        this.x = x;
        this.y = y;
        this.radius = radius;

    }

    @Override
    public void run() {
        // Condemnation has to be paced against freeing, not against the sweep
        // interval. Flying fast condemns far faster than the renderer can
        // afford to tear things down; without this the queue grew into the
        // tens of thousands, and the render thread spent its frames on
        // glDeleteBuffers while the terrain visibly stuttered.
        int pendingTeardown;
        synchronized (World.destroyChunks) {
            pendingTeardown = World.destroyChunks.size();
        }
        if (pendingTeardown > CONDEMN_BACKLOG_HIGH_WATER) {
            World.SWEEPER_IS_SLEEPING = true;
            World.WAKE_SWEEPER = true;
            return;
        }

        int outerRadius = keepRadius(radius, Game.OPT_CHUNK_SERIALIZE_RADIUS_MULTIPLIER);
        // Chunk coordinates are unbounded signed positions. World.sizeX/sizeY
        // are the world's BLOCK-space dimensions, so clamping chunk-space
        // bounds to them silently turned one whole side of the map into
        // sweep candidates once the camera travelled far enough East/North:
        // the entire loaded world zombified at once and never came back.
        int xLowerBound = x - outerRadius;
        int xUpperBound = x + outerRadius;
        int yLowerBound = y - outerRadius;
        int yUpperBound = y + outerRadius;

        // The render thread adds chunks under this monitor every frame; holding
        // it for a full scan of a 20k-element list stalled the frame loop for
        // tens of milliseconds at a time. Copy, release, then scan.
        ArrayList<WorldChunk> snapshot;
        synchronized (World.chunks) {
            snapshot = new ArrayList<>(World.chunks);
        }

        for (int i = 0, n = snapshot.size(); i < n; i++) {
            WorldChunk thisChunk = snapshot.get(i);
            if (thisChunk == null || thisChunk.queuedForDestroy) {
                // Queued chunks are the destroyer's business; chunks whose
                // fade is merely still playing out stay visitable so this
                // pass can notice the fade finishing.
                continue;
            }
            boolean outsideKeepArea = thisChunk.posX < xLowerBound
                    || thisChunk.posX > xUpperBound
                    || thisChunk.posY < yLowerBound
                    || thisChunk.posY > yUpperBound;
            if (!outsideKeepArea) {
                continue;
            }
            if (!thisChunk.isZombie) {
                thisChunk.serialize();
                thisChunk.isZombie = true;
                // Starts the fade-out clock the first time this chunk is seen
                // outside the keep area. A chunk that flickers back across the
                // boundary resumes its fade via cancelDestroyFade(), which also
                // releases its queue slot here.
                thisChunk.requestDestroyFade();
            }
            if (thisChunk.isDestroyFadeComplete()) {
                synchronized (World.destroyChunks) {
                    if (!thisChunk.queuedForDestroy) {
                        // Flag instead of destroyChunks.contains(): a linear
                        // scan of a multi-thousand entry list per candidate
                        // chunk made this sweep quadratic.
                        thisChunk.queuedForDestroy = true;
                        World.destroyChunks.add(thisChunk);
                    }
                }
            }
        }
        World.SWEEPER_IS_SLEEPING = true;
        World.WAKE_SWEEPER = true;
    }

    /**
     * Radius, in chunks, inside which chunks are kept alive.
     *
     * The sweep ring sits decidedly past the visible set -- draw distance plus
     * clearance -- for two reasons. (1) With no clearance a chunk at the very
     * edge of view flipped in and out of zombie/destroy-fade each time the
     * camera nudged across a chunk boundary, so far chunks visibly blinked
     * several times before settling. (2) A chunk that is no longer traversed
     * by the render loop cannot show its fade-out at all; World.render now
     * traverses a few rings past the draw distance, and the sweep ring stays
     * beyond that band so the fade completes where the player can see it.
     */
    static int keepRadius(int drawRadius, int serializeRadiusMultiplier) {
        return Math.max(drawRadius * Math.max(1, serializeRadiusMultiplier),
                drawRadius + SWEEP_MARGIN_RINGS);
    }
}
