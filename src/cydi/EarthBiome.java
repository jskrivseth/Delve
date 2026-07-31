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
