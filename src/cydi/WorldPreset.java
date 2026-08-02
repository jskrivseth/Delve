package cydi;

/** World-generation presets selectable when creating a save. */
public final class WorldPreset {

    public static final int EARTH = 0;
    public static final int MARS = 1;
    public static final int VENUS = 2;
    public static final int TRITON = 3;

    public static final int[] ALL = {EARTH, MARS, VENUS, TRITON};

    private WorldPreset() {
    }

    public static String nameOf(int preset) {
        return PlanetProfiles.get(preset).name;
    }

    public static int clamp(int preset) {
        for (int v : ALL) {
            if (v == preset) {
                return preset;
            }
        }
        return EARTH;
    }

    public static float baseMoveSpeed(int preset) {
        return PlanetProfiles.get(preset).baseMoveSpeed;
    }

    public static float flySpeedBonus(int preset) {
        return PlanetProfiles.get(preset).flySpeedBonus;
    }

    public static float jumpForce(int preset) {
        return PlanetProfiles.get(preset).jumpForce;
    }

    /** Caps accumulated upward velocity so low-gravity worlds cannot slingshot. */
    public static float maxJumpVelocity(int preset) {
        return PlanetProfiles.get(preset).maxJumpVelocity;
    }

    public static float gravity(int preset) {
        return PlanetProfiles.get(preset).gravity;
    }

    public static float drag(int preset) {
        return PlanetProfiles.get(preset).drag;
    }

    public static float solarIntensity(int preset) {
        return PlanetProfiles.get(preset).solarIntensity;
    }

    public static boolean hasEarthMoon(int preset) {
        return PlanetProfiles.get(preset).hasEarthMoon();
    }

    public static boolean hasDualMarsMoons(int preset) {
        return PlanetProfiles.get(preset).hasDualMarsMoons();
    }

    public static boolean hasNeptuneSkyBody(int preset) {
        return PlanetProfiles.get(preset).hasNeptuneSkyBody();
    }

    public static boolean hasClouds(int preset) {
        return PlanetProfiles.get(preset).hasClouds;
    }

    public static float cloudCoverage(int preset) {
        PlanetProfile profile = PlanetProfiles.get(preset);
        if (preset == WorldPreset.EARTH && Game.DAYS_WITH_WEATHER_CYCLES) {
            return Math.min(Math.max(Weather.cloudCoverage, 0.12f), 0.88f);
        }
        if (preset == WorldPreset.VENUS && Game.DAYS_WITH_WEATHER_CYCLES) {
            float heavy = 0.56f - (1.0f - Weather.cloudCoverage) * 0.30f;
            return Math.min(Math.max(heavy, 0.16f), 0.64f);
        }
        if (preset == WorldPreset.MARS && Game.DAYS_WITH_WEATHER_CYCLES) {
            // Keep Mars mostly clear with only occasional wispy decks.
            float clear = 0.82f + Weather.cloudCoverage * 0.12f;
            return Math.min(Math.max(clear, 0.74f), 0.96f);
        }
        return profile.cloudCoverage;
    }

    public static float cloudSharpness(int preset) {
        return PlanetProfiles.get(preset).cloudSharpness;
    }

    public static float cloudOpacity(int preset) {
        return PlanetProfiles.get(preset).cloudOpacity;
    }

    public static float cloudShadowStrength(int preset) {
        return PlanetProfiles.get(preset).cloudShadowStrength;
    }

    public static float cloudBaseHeight(int preset) {
        return PlanetProfiles.get(preset).cloudBaseHeight;
    }

    public static float cloudLayerDepth(int preset) {
        return PlanetProfiles.get(preset).cloudLayerDepth;
    }

    public static float cloudSpeed(int preset) {
        return PlanetProfiles.get(preset).cloudSpeed;
    }

    public static int cloudLayerCount(int preset) {
        return PlanetProfiles.get(preset).cloudLayerCount;
    }
}
