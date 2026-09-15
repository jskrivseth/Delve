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
        int outerRadius = (radius * Game.OPT_CHUNK_SERIALIZE_RADIUS_MULTIPLIER);
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
}
