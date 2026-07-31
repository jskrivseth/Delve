/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Chunk persistence. Saves live under a dedicated directory so edited chunks
 * survive being swept out of memory and regenerated.
 */
public class Serializer {

    private static final File SAVE_DIR = new File("saves");

    private static File fileFor(String filename) {
        // Chunks live inside the active world's directory so multiple saves do
        // not overwrite each other.
        SaveGame save = Game.CURRENT_SAVE;
        if (save != null) {
            return save.chunkFile(filename);
        }
        if (!SAVE_DIR.exists()) {
            SAVE_DIR.mkdirs();
        }
        return new File(SAVE_DIR, filename);
    }

    public static boolean exists(String filename) {
        return fileFor(filename).isFile();
    }

    /**
     * Removes a chunk file so the chunk regenerates from noise next time. Used
     * to discard saves that cannot be read back.
     */
    public static void delete(String filename) {
        File file = fileFor(filename);
        if (file.isFile() && !file.delete()) {
            System.err.println("Could not delete unreadable chunk " + filename);
        }
    }

    public static boolean serializeArray(int[][][] array, String filename) {
        // Written to a temporary file and moved into place. Writing directly
        // truncates the existing file first, so a crash or a concurrent reader
        // would see a half-written chunk, and an unreadable chunk leaves a
        // permanent hole in the world.
        File target = fileFor(filename);
        File temp = new File(target.getParentFile(), filename + ".tmp");
        try {
            try (FileOutputStream fout = new FileOutputStream(temp);
                 ObjectOutputStream oos = new ObjectOutputStream(fout)) {
                oos.writeObject(array);
            }
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ex) {
            System.err.println("Failed saving chunk " + filename + ": " + ex.getMessage());
            temp.delete();
            return false;
        }
    }

    public static int[][][] deserializeArray(String filename) {
        File file = fileFor(filename);
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream fin = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(fin)) {
            return (int[][][]) ois.readObject();
        } catch (Exception ex) {
            System.err.println("Failed loading chunk " + filename + ": " + ex.getMessage());
            delete(filename);
            return null;
        }
    }
}
