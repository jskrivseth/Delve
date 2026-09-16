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
        long ticket = PipeTimer.begin();
        try {
            synchronized (chunkToProcess) {
                if (!World.isChunkInCurrentBounds(chunkToProcess)) {
                    chunkToProcess.isBuilding = false;
                    chunkToProcess.isRefreshing = false;
                    return;
                }
                try {
                    chunkToProcess.buildMesh();
                } finally {
                    chunkToProcess.isBuilding = false;
                    chunkToProcess.isRefreshing = false;
                }
            }
        } finally {
            PipeTimer.end(PipeTimer.MESH, ticket);
        }
    }
}
