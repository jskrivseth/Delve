/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package delve.world;

/**
 *
 * @author Jesse
 */
public class WorldChunkLoadThread implements Runnable {

    WorldChunk chunkToProcess;

    public WorldChunkLoadThread(WorldChunk chunkToProcess) {
        this.chunkToProcess = chunkToProcess;

    }

    @Override
    public void run() {
        long ticket = delve.world.PipeTimer.begin();
        synchronized (chunkToProcess) {
            if (!World.isChunkInCurrentBounds(chunkToProcess)) {
                chunkToProcess.isGenerating = false;
                return;
            }
            // Always go through generate(). It already restores a saved chunk
            // when one exists and falls back to noise when it does not, and it
            // is what marks the chunk generated. Calling load() directly left
            // the chunk flagged ungenerated whether or not a save existed, so
            // it was queued for loading every frame and never drawn.
            chunkToProcess.generate();
        }
        delve.world.PipeTimer.end(delve.world.PipeTimer.GEN, ticket);
    }
}
