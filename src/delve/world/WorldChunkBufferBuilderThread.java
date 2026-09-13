/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package delve.world;

/**
 *
 * @author Jesse
 */
public class WorldChunkBufferBuilderThread implements Runnable {

    WorldChunk chunkToProcess;

    public WorldChunkBufferBuilderThread(WorldChunk chunkToProcess) {
        this.chunkToProcess = chunkToProcess;

    }

    @Override
    public void run() {
        synchronized (chunkToProcess) {
            try {
                chunkToProcess.buildMesh();
            } finally {
                chunkToProcess.isBuilding = false;
                chunkToProcess.isRefreshing = false;
            }
        }
    }
}
