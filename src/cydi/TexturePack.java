package cydi;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runtime-swappable texture packs in Minecraft's layouts.
 *
 * Two formats are supported:
 * <ul>
 *   <li><b>Classic</b> (Beta and earlier): a single {@code terrain.png} atlas of
 *       16x16 tiles, with {@code terrain/sun.png} and {@code terrain/moon.png}.</li>
 *   <li><b>Modern</b> (1.5+): individual block files under
 *       {@code assets/minecraft/textures/block/}, which are assembled into an
 *       atlas here, plus {@code environment/sun.png} and
 *       {@code environment/moon_phases.png}.</li>
 * </ul>
 *
 * A pack may be a directory or a zip. Because every block's UVs are baked
 * against fixed atlas slots, swapping a pack only replaces GL textures and never
 * requires chunks to be re-meshed.
 */
public class TexturePack {

    private static final File PACK_DIR = new File("texturepacks");
    private static final String BUILTIN = "Default";

    /** Tile size and atlas dimensions the engine's UVs assume. */
    private static final int TILE = 16;
    private static final int TILES_PER_ROW = 16;
    private static final int ATLAS_PX = TILE * TILES_PER_ROW;

    /**
     * Modern packs store one file per block, so each atlas slot the engine uses
     * is mapped to the block names that may supply it. The first name found
     * wins, which lets one entry cover several Minecraft versions.
     */
    private static final Object[][] MODERN_SLOTS = {
        {0, 0, "grass_block_top", "grass_top"},
        {3, 0, "grass_block_side", "grass_side"},
        {2, 0, "dirt"},
        {1, 0, "stone"},
        {0, 1, "cobblestone"},
        {2, 1, "sand"},
        {3, 1, "gravel"},
        {4, 1, "oak_log", "log_oak"},
        {5, 1, "oak_log_top", "log_oak_top"},
        {4, 0, "oak_planks", "planks_oak"},
        {4, 3, "oak_leaves", "leaves_oak"},
        {7, 0, "bricks", "brick"},
        {1, 1, "bedrock"},
        {1, 3, "glass"},
        {2, 4, "snow"},
        {4, 4, "grass_block_snow", "grass_side_snowed"},
        {13, 12, "water_still", "water"},
        {8, 4, "clay"},
        {0, 12, "sandstone"},
        {14, 1, "red_sand"},
        {0, 11, "red_sandstone"},
        {6, 0, "andesite"},
        {6, 1, "diorite"},
        {6, 2, "granite"},
        {4, 2, "mossy_cobblestone"},
        {1, 2, "deepslate"},
        {7, 2, "short_grass", "grass", "fern"},
        {13, 0, "dandelion"},
        {12, 1, "red_mushroom"},
        {14, 2, "short_dry_grass", "dead_bush", "fern"},
        {14, 3, "mud", "dirt"},
        {14, 4, "powder_snow", "snow"},
        {15, 4, "mud", "coarse_dirt", "dirt"},
        {15, 2, "basalt", "blackstone"},
        {15, 3, "end_stone", "sandstone_top"},
        {15, 5, "blue_ice", "packed_ice", "ice"},
        {15, 6, "crying_obsidian", "obsidian"},
        {15, 7, "tuff", "gravel"},
    };

    private final String name;
    private final File source;

    private TexturePack(String name, File source) {
        this.name = name;
        this.source = source;
    }

    public String getName() {
        return name;
    }

    public boolean isBuiltin() {
        return source == null;
    }

    /** The built-in pack plus every pack found in {@code texturepacks/}. */
    public static List<TexturePack> discover() {
        List<TexturePack> packs = new ArrayList<>();
        packs.add(new TexturePack(BUILTIN, null));

        if (!PACK_DIR.exists()) {
            PACK_DIR.mkdirs();
        }
        File[] entries = PACK_DIR.listFiles();
        if (entries == null) {
            return packs;
        }
        java.util.Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File entry : entries) {
            if (entry.isDirectory() || entry.getName().toLowerCase().endsWith(".zip")) {
                packs.add(new TexturePack(stripExtension(entry.getName()), entry));
            }
        }
        return packs;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Reads a file from the pack, or null when absent.
     *
     * Zip entries are matched case-insensitively and ignore any single leading
     * directory, since packs are often zipped with a wrapping folder.
     */
    private byte[] read(String path) {
        if (source == null) {
            return readClasspath(path);
        }
        try {
            if (source.isDirectory()) {
                File file = new File(source, path);
                return file.isFile() ? java.nio.file.Files.readAllBytes(file.toPath()) : null;
            }
            try (ZipFile zip = new ZipFile(source)) {
                ZipEntry entry = findEntry(zip, path);
                if (entry == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String path) {
        String wanted = path.toLowerCase();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        ZipEntry nested = null;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName().toLowerCase().replace('\\', '/');
            if (entryName.equals(wanted)) {
                return entry;
            }
            int slash = entryName.indexOf('/');
            if (slash >= 0 && entryName.substring(slash + 1).equals(wanted)) {
                nested = entry;
            }
        }
        return nested;
    }

    private static byte[] readClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = TexturePack.class.getResourceAsStream(resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            // fall through to the working directory
        }
        File file = new File(path);
        if (file.isFile()) {
            try {
                return java.nio.file.Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private BufferedImage readImage(String path) {
        byte[] bytes = read(path);
        if (bytes == null) {
            return null;
        }
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The terrain atlas, either taken directly from a classic pack or assembled
     * from a modern pack's individual block files.
     */
    public BufferedImage loadTerrainAtlas() {
        BufferedImage classic = readImage("terrain.png");
        if (classic != null) {
            return classic;
        }
        BufferedImage assembled = assembleModernAtlas();
        if (assembled != null) {
            return assembled;
        }
        // Fall back to the built-in art so a partial pack still renders.
        return new TexturePack(BUILTIN, null).readImage("media/art/terrain.png");
    }

    /** Builds an atlas by placing each modern block file into its engine slot. */
    private BufferedImage assembleModernAtlas() {
        BufferedImage base = new TexturePack(BUILTIN, null).readImage("media/art/terrain.png");
        if (base == null) {
            return null;
        }
        BufferedImage atlas = new BufferedImage(ATLAS_PX, ATLAS_PX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        // Start from the default art so slots the pack omits still look right.
        g.drawImage(base, 0, 0, ATLAS_PX, ATLAS_PX, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int placed = 0;
        for (Object[] slot : MODERN_SLOTS) {
            int col = (Integer) slot[0];
            int row = (Integer) slot[1];
            for (int i = 2; i < slot.length; i++) {
                String blockName = (String) slot[i];
                BufferedImage tile = readModernBlock(blockName);
                if (tile != null) {
                    // Animated textures are a vertical strip; use the first frame.
                    int frame = Math.min(tile.getWidth(), tile.getHeight());
                    g.drawImage(tile,
                            col * TILE, row * TILE, col * TILE + TILE, row * TILE + TILE,
                            0, 0, frame, frame, null);
                    placed++;
                    break;
                }
            }
        }
        g.dispose();
        return placed > 0 ? atlas : null;
    }

    private BufferedImage readModernBlock(String blockName) {
        BufferedImage image = readImage("assets/minecraft/textures/block/" + blockName + ".png");
        if (image == null) {
            image = readImage("assets/minecraft/textures/blocks/" + blockName + ".png");
        }
        return image;
    }

    /** Sun disc texture, or null to fall back to a plain quad. */
    public BufferedImage loadSun() {
        BufferedImage image = readImage("assets/minecraft/textures/environment/sun.png");
        if (image == null) {
            image = readImage("terrain/sun.png");
        }
        if (image == null) {
            image = readImage("environment/sun.png");
        }
        if (image == null && !isBuiltin()) {
            return new TexturePack(BUILTIN, null).loadSun();
        }
        if (image == null) {
            image = readImage("media/art/sun.png");
        }
        return image;
    }

    /**
     * Moon texture. Modern packs ship a 4x2 grid of phases; classic packs ship a
     * single disc, which is expanded here into the same grid so the renderer has
     * one layout to deal with.
     */
    public BufferedImage loadMoonPhases() {
        BufferedImage image = readImage("assets/minecraft/textures/environment/moon_phases.png");
        if (image != null) {
            return image;
        }
        BufferedImage single = readImage("terrain/moon.png");
        if (single == null) {
            single = readImage("environment/moon.png");
        }
        if (single != null) {
            return expandSingleMoon(single);
        }
        if (!isBuiltin()) {
            return new TexturePack(BUILTIN, null).loadMoonPhases();
        }
        return readImage("media/art/moon_phases.png");
    }

    /** Repeats a single moon image across all eight phase cells. */
    private static BufferedImage expandSingleMoon(BufferedImage single) {
        int tile = single.getWidth();
        BufferedImage grid = new BufferedImage(tile * 4, tile * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = grid.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (int i = 0; i < 8; i++) {
            g.drawImage(single, (i % 4) * tile, (i / 4) * tile, null);
        }
        g.dispose();
        return grid;
    }

    @Override
    public String toString() {
        return name;
    }
}
