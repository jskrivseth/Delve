/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

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
        synchronized (chunkToProcess) {
            // Always go through generate(). It already restores a saved chunk
            // when one exists and falls back to noise when it does not, and it
            // is what marks the chunk generated. Calling load() directly left
            // the chunk flagged ungenerated whether or not a save existed, so
            // it was queued for loading every frame and never drawn.
            chunkToProcess.generate();
        }
    }
}
