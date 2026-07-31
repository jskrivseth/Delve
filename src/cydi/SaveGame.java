package cydi;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Named world saves.
 *
 * Each world owns a directory holding its chunk files plus a small metadata
 * file. Persisting the seed matters as much as the chunks: without it the world
 * regenerates differently around the player's edits on every load.
 */
public class SaveGame {

    private static final File ROOT = new File("saves");

    public final String name;
    public long seed;
    public int worldPreset = WorldPreset.EARTH;
    public double playerX, playerY, playerZ;
    public float yaw, pitch;
    public float timeOfDay = 0.30f;
    public int dayCount;
    public boolean hasPlayerPosition;

    public SaveGame(String name) {
        this.name = name;
    }

    public File directory() {
        return new File(ROOT, name);
    }

    /** Chunk files live inside the world directory. */
    public File chunkFile(String fileName) {
        File dir = directory();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, fileName);
    }

    private File metadataFile() {
        return new File(directory(), "world.properties");
    }

    /** Names of every world on disk, newest first. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        File[] dirs = ROOT.listFiles(File::isDirectory);
        if (dirs == null) {
            return names;
        }
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File dir : dirs) {
            names.add(dir.getName());
        }
        return names;
    }

    public static boolean exists(String name) {
        return new File(ROOT, name).isDirectory();
    }

    /** Picks an unused default name such as "World 3". */
    public static String nextDefaultName() {
        for (int i = 1; i < 1000; i++) {
            String candidate = "World " + i;
            if (!exists(candidate)) {
                return candidate;
            }
        }
        return "World " + System.currentTimeMillis();
    }

    public static SaveGame create(String name, long seed) {
        return create(name, seed, WorldPreset.EARTH);
    }

    public static SaveGame create(String name, long seed, int worldPreset) {
        SaveGame save = new SaveGame(name);
        save.seed = seed;
        save.worldPreset = WorldPreset.clamp(worldPreset);
        save.directory().mkdirs();
        save.write();
        return save;
    }

    public static SaveGame load(String name) {
        SaveGame save = new SaveGame(name);
        File file = save.metadataFile();
        if (!file.isFile()) {
            // A world directory without metadata predates seed persistence. The
            // seed is derived from the name rather than randomised, so the world
            // at least regenerates the same way on every load instead of
            // rebuilding differently around its saved chunks each time.
            save.seed = seedFromName(name);
            System.err.println("World '" + name + "' has no metadata; "
                    + "using a seed derived from its name (" + save.seed + ").");
            return save;
        }
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Failed reading world metadata: " + e.getMessage());
            return save;
        }
        save.seed = parseLong(props.getProperty("seed"), 0L);
        save.worldPreset = WorldPreset.clamp((int) parseLong(props.getProperty("worldPreset"), WorldPreset.EARTH));
        save.playerX = parseDouble(props.getProperty("playerX"), 0);
        save.playerY = parseDouble(props.getProperty("playerY"), 0);
        save.playerZ = parseDouble(props.getProperty("playerZ"), 0);
        save.yaw = (float) parseDouble(props.getProperty("yaw"), 0);
        save.pitch = (float) parseDouble(props.getProperty("pitch"), 0);
        save.timeOfDay = (float) parseDouble(props.getProperty("timeOfDay"), 0.30);
        save.dayCount = (int) parseLong(props.getProperty("dayCount"), 0);
        save.hasPlayerPosition = props.containsKey("playerX");
        return save;
    }

    public void write() {
        Properties props = new Properties();
        props.setProperty("seed", Long.toString(seed));
        props.setProperty("worldPreset", Integer.toString(WorldPreset.clamp(worldPreset)));
        props.setProperty("playerX", Double.toString(playerX));
        props.setProperty("playerY", Double.toString(playerY));
        props.setProperty("playerZ", Double.toString(playerZ));
        props.setProperty("yaw", Float.toString(yaw));
        props.setProperty("pitch", Float.toString(pitch));
        props.setProperty("timeOfDay", Float.toString(timeOfDay));
        props.setProperty("dayCount", Integer.toString(dayCount));

        File dir = directory();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        Path path = metadataFile().toPath();
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            props.store(writer, "Delve world");
        } catch (IOException e) {
            System.err.println("Failed writing world metadata: " + e.getMessage());
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Stable seed for worlds saved before the seed was recorded. */
    private static long seedFromName(String name) {
        long hash = 1469598103934665603L;
        for (int i = 0; i < name.length(); i++) {
            hash = (hash ^ name.charAt(i)) * 1099511628211L;
        }
        return hash;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
