package cydi;

/**
 * Tunable biome profile.
 *
 * The climate center drives blending; the scalar coefficients shape terrain and
 * vegetation tendencies when several biomes mix.
 */
public final class BiomeDefinition {

    public static final int TUNDRA = 0;
    public static final int DESERT = 1;
    public static final int FOREST = 2;
    public static final int GRASSY = 3;

    public final int id;
    public final String name;
    public final float centerTemp;
    public final float centerMoisture;
    public final float reliefScale;
    public final float baseBias;
    public final float dramaBias;
    public final float treeBias;
    public final float wetlandBias;

    public BiomeDefinition(int id, String name,
                           float centerTemp, float centerMoisture,
                           float reliefScale, float baseBias,
                           float dramaBias, float treeBias, float wetlandBias) {
        this.id = id;
        this.name = name;
        this.centerTemp = centerTemp;
        this.centerMoisture = centerMoisture;
        this.reliefScale = reliefScale;
        this.baseBias = baseBias;
        this.dramaBias = dramaBias;
        this.treeBias = treeBias;
        this.wetlandBias = wetlandBias;
    }

    public static final BiomeDefinition[] ALL = {
            new BiomeDefinition(TUNDRA, "Tundra", 0.18f, 0.45f, 1.12f, 0.052f, 0.86f, 0.16f, 0.42f),
            new BiomeDefinition(DESERT, "Desert", 0.85f, 0.18f, 0.72f, -0.030f, 0.55f, 0.06f, 0.04f),
            new BiomeDefinition(FOREST, "Forest", 0.55f, 0.82f, 1.06f, 0.015f, 1.20f, 1.00f, 0.62f),
            new BiomeDefinition(GRASSY, "Grassy", 0.52f, 0.50f, 1.12f, 0.000f, 0.96f, 0.62f, 0.38f),
    };
}
