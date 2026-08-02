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
        return primaryBiome();
    }

    public int primaryBiome() {
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

    public int secondaryBiome() {
        int first = primaryBiome();
        int second = first == 0 ? 1 : 0;
        float best = weights[second];
        for (int i = 0; i < weights.length; i++) {
            if (i == first) {
                continue;
            }
            float w = weights[i];
            if (w > best) {
                best = w;
                second = i;
            }
        }
        return second;
    }

    /** How close the top two biomes are; higher means a wider border zone. */
    public float transitionStrength() {
        int primary = primaryBiome();
        int secondary = secondaryBiome();
        float total = weights[primary] + weights[secondary];
        if (total <= 0.0f) {
            return 0.0f;
        }
        return clamp01(1.0f - Math.abs(weights[primary] - weights[secondary]) / total);
    }

    /**
     * Returns a copy whose two strongest biomes are nudged together or apart
     * based on a deterministic dither value in [0, 1].
     */
    public BiomeBlend dithered(float noise) {
        BiomeBlend blend = new BiomeBlend();
        System.arraycopy(weights, 0, blend.weights, 0, weights.length);

        int primary = primaryBiome();
        int secondary = secondaryBiome();
        float primaryWeight = weights[primary];
        float secondaryWeight = weights[secondary];
        float pairTotal = primaryWeight + secondaryWeight;
        if (pairTotal <= 0.0f) {
            return blend;
        }

        // The closer the top two weights are, the wider the transition band.
        float borderStrength = transitionStrength();
        if (borderStrength <= 0.001f) {
            return blend;
        }

        float targetSecondary = secondaryWeight / pairTotal;
        targetSecondary += (noise - 0.5f) * borderStrength * 0.58f;
        targetSecondary = clamp01(targetSecondary);

        float newSecondary = pairTotal * targetSecondary;
        float delta = newSecondary - secondaryWeight;
        blend.weights[primary] = primaryWeight - delta;
        blend.weights[secondary] = secondaryWeight + delta;
        return blend;
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
