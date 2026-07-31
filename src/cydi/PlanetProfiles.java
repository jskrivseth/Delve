package cydi;

/** Registry for per-preset planet profiles. */
public final class PlanetProfiles {
    private static final PlanetProfile EARTH = new EarthProfile();
    private static final PlanetProfile MARS = new MarsProfile();
    private static final PlanetProfile VENUS = new VenusProfile();
    private static final PlanetProfile TRITON = new TritonProfile();

    private PlanetProfiles() {
    }

    public static PlanetProfile get(int preset) {
        return switch (WorldPreset.clamp(preset)) {
            case WorldPreset.MARS -> MARS;
            case WorldPreset.VENUS -> VENUS;
            case WorldPreset.TRITON -> TRITON;
            default -> EARTH;
        };
    }
}
