package cydi;

/** Blended biome weights at one world coordinate. */
public final class BiomeBlend {
    private final float[] weights = new float[BiomeDefinition.ALL.length];

    public float weight(int biomeId) {
        return weights[biomeId];
    }

    public void setWeight(int biomeId, float weight) {
        weights[biomeId] = weight;
    }

    public int dominantBiome() {
        int idx = 0;
        float best = weights[0];
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > best) {
                best = weights[i];
                idx = i;
            }
        }
        return idx;
    }
}
