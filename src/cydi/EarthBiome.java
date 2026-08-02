package cydi;

/** Real-world-inspired biome categories used by terrain generation. */
public final class EarthBiome {

    public static final int HOT_DESERT = 0;
    public static final int SAVANNA = 1;
    public static final int SHRUBLAND = 2;
    public static final int TEMPERATE_GRASSLAND = 3;
    public static final int TEMPERATE_FOREST = 4;
    public static final int BOREAL_FOREST = 5;
    public static final int TUNDRA = 6;
    public static final int TROPICAL_RAINFOREST = 7;
    public static final int WETLAND = 8;
    public static final int ALPINE = 9;

    /**
     * Relief multiplier per biome, indexed by biome id.
     *
     * These are blended across neighbouring biomes in climate space rather than
     * switched on the winning biome, so terrain shape changes gradually across a
     * border instead of stepping at the seam.
     */
    public static final float[] RELIEF_SCALE = {
            0.80f, // HOT_DESERT: broad dune fields
            0.88f, // SAVANNA
            1.00f, // SHRUBLAND
            0.84f, // TEMPERATE_GRASSLAND: rolling
            1.04f, // TEMPERATE_FOREST
            1.12f, // BOREAL_FOREST
            1.08f, // TUNDRA
            0.96f, // TROPICAL_RAINFOREST
            0.60f, // WETLAND: nearly flat
            1.34f, // ALPINE: dramatic
    };

    /** Base elevation bias per biome, blended the same way as {@link #RELIEF_SCALE}. */
    public static final float[] BASE_BIAS = {
            -0.012f, // HOT_DESERT
            -0.004f, // SAVANNA
            0.004f,  // SHRUBLAND
            0.000f,  // TEMPERATE_GRASSLAND
            0.008f,  // TEMPERATE_FOREST
            0.018f,  // BOREAL_FOREST
            0.026f,  // TUNDRA
            0.004f,  // TROPICAL_RAINFOREST
            -0.022f, // WETLAND
            0.050f,  // ALPINE
    };

    /**
     * Grass and foliage tint per biome, as an RGB multiplier over the atlas art.
     *
     * Normalised to constant luminance below, so a biome shifts the hue of the
     * ground without making it brighter or darker. Left un-normalised these read
     * as glowing paint rather than vegetation.
     */
    public static final float[][] TINT = {
            {1.32f, 1.12f, 0.66f}, // HOT_DESERT: bleached and dry
            {1.26f, 1.10f, 0.55f}, // SAVANNA: golden
            {1.14f, 1.06f, 0.72f}, // SHRUBLAND: olive
            {1.00f, 1.08f, 0.76f}, // TEMPERATE_GRASSLAND
            {0.84f, 1.02f, 0.72f}, // TEMPERATE_FOREST: rich
            {0.70f, 0.92f, 0.84f}, // BOREAL_FOREST: cool and dark
            {0.84f, 0.94f, 1.02f}, // TUNDRA: pale and cold
            {0.70f, 1.12f, 0.60f}, // TROPICAL_RAINFOREST: vivid
            {0.78f, 1.00f, 0.66f}, // WETLAND: murky
            {0.90f, 0.96f, 1.04f}, // ALPINE: washed out
    };

    static {
        for (float[] t : TINT) {
            float luma = 0.299f * t[0] + 0.587f * t[1] + 0.114f * t[2];
            t[0] /= luma;
            t[1] /= luma;
            t[2] /= luma;
        }
    }

    public static final int COUNT = 10;

    private EarthBiome() {
    }

    public static String nameOf(int biome) {
        return switch (biome) {
            case HOT_DESERT -> "Hot Desert";
            case SAVANNA -> "Savanna";
            case SHRUBLAND -> "Shrubland";
            case TEMPERATE_GRASSLAND -> "Temperate Grassland";
            case TEMPERATE_FOREST -> "Temperate Forest";
            case BOREAL_FOREST -> "Boreal Forest";
            case TUNDRA -> "Tundra";
            case TROPICAL_RAINFOREST -> "Tropical Rainforest";
            case WETLAND -> "Wetland";
            case ALPINE -> "Alpine";
            default -> "Temperate Grassland";
        };
    }
}
