package cydi;

/** Immutable per-preset behavior and tuning values. */
public abstract class PlanetProfile {
    public final int id;
    public final String name;
    public final float baseMoveSpeed;
    public final float flySpeedBonus;
    public final float jumpForce;
    public final float maxJumpVelocity;
    public final float gravity;
    public final float drag;
    public final float solarIntensity;
    public final boolean hasClouds;
    public final float cloudCoverage;
    public final float cloudSharpness;
    public final float cloudOpacity;
    public final float cloudShadowStrength;
    public final float cloudBaseHeight;
    public final float cloudLayerDepth;
    public final float cloudSpeed;

    protected PlanetProfile(int id, String name,
                            float baseMoveSpeed, float flySpeedBonus,
                            float jumpForce, float maxJumpVelocity,
                            float gravity, float drag, float solarIntensity,
                            boolean hasClouds, float cloudCoverage, float cloudSharpness,
                            float cloudOpacity, float cloudShadowStrength,
                            float cloudBaseHeight, float cloudLayerDepth, float cloudSpeed) {
        this.id = id;
        this.name = name;
        this.baseMoveSpeed = baseMoveSpeed;
        this.flySpeedBonus = flySpeedBonus;
        this.jumpForce = jumpForce;
        this.maxJumpVelocity = maxJumpVelocity;
        this.gravity = gravity;
        this.drag = drag;
        this.solarIntensity = solarIntensity;
        this.hasClouds = hasClouds;
        this.cloudCoverage = cloudCoverage;
        this.cloudSharpness = cloudSharpness;
        this.cloudOpacity = cloudOpacity;
        this.cloudShadowStrength = cloudShadowStrength;
        this.cloudBaseHeight = cloudBaseHeight;
        this.cloudLayerDepth = cloudLayerDepth;
        this.cloudSpeed = cloudSpeed;
    }

    public boolean hasEarthMoon() {
        return false;
    }

    public boolean hasDualMarsMoons() {
        return false;
    }

    public boolean hasNeptuneSkyBody() {
        return false;
    }
}

final class EarthProfile extends PlanetProfile {
    EarthProfile() {
        super(WorldPreset.EARTH, "Earth",
                2.85f, 2.0f,
                0.18f, 0.21f,
                0.005f, 1.075f,
                1.0f,
                true, 0.48f, 0.075f,
                0.56f, 0.32f,
                92.0f, 34.0f, 0.12f);
    }

    @Override
    public boolean hasEarthMoon() {
        return true;
    }
}

final class MarsProfile extends PlanetProfile {
    MarsProfile() {
        super(WorldPreset.MARS, "Mars",
                3.15f, 1.9f,
                0.15f, 0.18f,
                0.0019f, 1.050f,
                0.58f,
                false, 0.0f, 0.0f,
                0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
    }

    @Override
    public boolean hasDualMarsMoons() {
        return true;
    }
}

final class VenusProfile extends PlanetProfile {
    VenusProfile() {
        super(WorldPreset.VENUS, "Venus",
                1.20f, 0.9f,
                0.22f, 0.24f,
                0.0062f, 1.230f,
                1.9f,
                true, 0.64f, 0.050f,
                0.94f, 0.66f,
                78.0f, 56.0f, 0.06f);
    }
}

final class TritonProfile extends PlanetProfile {
    TritonProfile() {
        super(WorldPreset.TRITON, "Triton",
                2.25f, 1.6f,
                0.10f, 0.13f,
                0.00045f, 1.040f,
                0.085f,
                false, 0.0f, 0.0f,
                0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
    }

    @Override
    public boolean hasNeptuneSkyBody() {
        return true;
    }
}
