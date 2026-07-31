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

    public static boolean serializeArray(int[][][] array, String filename) {
        try (FileOutputStream fout = new FileOutputStream(fileFor(filename));
             ObjectOutputStream oos = new ObjectOutputStream(fout)) {
            oos.writeObject(array);
            return true;
        } catch (Exception ex) {
            System.err.println("Failed saving chunk " + filename + ": " + ex.getMessage());
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
            return null;
        }
    }
}
