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
            for (int i = 0; i < chunks.size(); i++) {
                WorldChunk thisChunk = chunks.get(i);
                if (thisChunk != null && thisChunk.isZombie && thisChunk.posX < xLowerBound || thisChunk.posX > xUpperBound || thisChunk.posY < yLowerBound || thisChunk.posY > yUpperBound) {
                    thisChunk.serialize();
                    thisChunk.isZombie = true;
                    if (!World.destroyChunks.contains(thisChunk)) {
                        World.destroyChunks.add(thisChunk);
                    }

                }
            }
            //}
        }
        World.SWEEPER_IS_SLEEPING = true;
        World.WAKE_SWEEPER = true;
    }
}
