/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import org.joml.Vector2f;
import java.util.*;

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
        synchronized (World.destroyChunks) {
            int outerRadius = (radius * Game.OPT_CHUNK_SERIALIZE_RADIUS_MULTIPLIER);
            int xLowerBound = Math.max(x - outerRadius, 0);
            int xUpperBound = Math.min(x + outerRadius, World.sizeX);
            int yLowerBound = Math.max(y - outerRadius, 0);
            int yUpperBound = Math.min(y + outerRadius, World.sizeY);
            //System.out.println("xLowerBound: " + xLowerBound + " - xUpperBound: " + xUpperBound + " - yLowerBound: " + yLowerBound + " - yUpperBound: " + yUpperBound );
            //System.out.println("0 - " + (World.sizeX / WorldChunk.sizeX));

            //for (int i = 0; i < World.sizeX; i++) {
            //   for (int j = 0; j < World.sizeY; j++) {
            // World.chunks is mutated (add/remove) from the main thread; without
            // this lock this background scan can read a torn/resized array
            // mid-mutation, intermittently throwing or silently skipping chunks.
            synchronized (World.chunks) {
                for (int i = 0; i < chunks.size(); i++) {
                    WorldChunk thisChunk = chunks.get(i);
                    if (thisChunk == null) {
                        continue;
                    }
                    // Parenthesised deliberately. Without it && bound tighter than
                    // ||, so the null check only guarded the first comparison and
                    // a chunk was swept merely for sitting past one bound.
                    boolean outsideKeepArea = thisChunk.posX < xLowerBound
                            || thisChunk.posX > xUpperBound
                            || thisChunk.posY < yLowerBound
                            || thisChunk.posY > yUpperBound;
                    if (outsideKeepArea) {
                        thisChunk.serialize();
                        thisChunk.isZombie = true;
                        // Idempotent -- starts the fade-out clock the first
                        // time this chunk is seen outside the keep area, and
                        // does nothing on later sweeps while it's still
                        // fading. Only queue it for actual GPU teardown once
                        // that fade has fully played out.
                        //
                        // NOTE ON SCOPE: with the default keep-area radius
                        // (OPT_CHUNK_SERIALIZE_RADIUS_MULTIPLIER == 1), a
                        // chunk stops being visited by World.render()'s main
                        // loop at essentially the same instant it becomes
                        // sweep-eligible here, so this delay mostly does not
                        // produce a *visible* fade for the common "walking
                        // away" case -- that case already fades smoothly via
                        // the render loop's own distance-based edgeFade
                        // before a chunk ever reaches this boundary. What
                        // this really guarantees is (a) GPU teardown never
                        // happens mid-fade, and (b) a chunk that flickers
                        // back and forth across the boundary resumes its
                        // fade continuously with no pop (see
                        // WorldChunk.requestDestroyFade/cancelDestroyFade).
                        // A true visible fade-out for abrupt cases (e.g. draw
                        // distance reduced in settings) would need the render
                        // loop itself to keep drawing a margin of chunks
                        // beyond the live radius while they finish fading --
                        // intentionally out of scope here to avoid touching
                        // the chunk generation/culling hot path.
                        thisChunk.requestDestroyFade();
                        if (thisChunk.isDestroyFadeComplete()
                                && !World.destroyChunks.contains(thisChunk)) {
                            World.destroyChunks.add(thisChunk);
                        }
                    }
                }
            }
            //}
        }
        World.SWEEPER_IS_SLEEPING = true;
        World.WAKE_SWEEPER = true;
    }
}
