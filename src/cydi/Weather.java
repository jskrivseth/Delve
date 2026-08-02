package cydi;

/**
 * World weather as a pure function of world seed and elapsed game time.
 *
 * This deliberately mirrors how the terrain itself is generated: cloud
 * coverage, wind and turbulence are all sampled from smooth, seeded 1D value
 * noise over the continuous game-day timeline, the same way terrain height is
 * sampled from seeded noise over world position. There is no stored random
 * walk and no per-frame state to advance -- {@link #recompute(double)} always
 * derives the exact same weather for a given (seed, totalGameDays) pair no
 * matter when, how often, or in what order it is called. That means the same
 * seed reproduces the same weather on day N whether you play there in one
 * sitting or reload the save a dozen times, and a save can jump straight to
 * the correct weather for its stored day/time instead of always restarting
 * from a fresh roll.
 *
 * Call {@link #update(double)} once per frame with the total elapsed game
 * days (whole days plus the fractional time of day). Call {@link #reset(double)}
 * once when a world is loaded, with the same quantity restored from the save,
 * so weather matches immediately rather than starting over at "day zero".
 */
public final class Weather {

    public enum Condition {
        SUNNY         ("Sunny"),
        SCATTERED     ("Scattered Clouds"),
        PLEASANT      ("Partly Cloudy"),
        MOSTLY_CLOUDY ("Mostly Cloudy"),
        OVERCAST      ("Overcast"),
        STORM         ("Storm");

        public final String label;
        Condition(String label) { this.label = label; }
    }

    // --- Tuning constants ---------------------------------------------------

    /** `n` thresholds (descending) marking the boundaries between the 5 base
     *  named conditions. These are picked from `n`'s actual empirical
     *  percentiles (see WeatherProbe3-style sampling of rawCoverageNoise)
     *  rather than an analytical guess -- a first attempt assumed a
     *  bell-shaped distribution and picked edges that, while directionally
     *  right, left OVERCAST at only ~0.1% of samples and STORM completely
     *  unreachable (it can only escalate out of OVERCAST). These values
     *  target roughly SUNNY 8%, SCATTERED 38%, PLEASANT 40%, MOSTLY_CLOUDY
     *  9%, OVERCAST 5%, verified with WeatherProbe2. */
    private static final float N_SUNNY_EDGE     = 0.74f; // n >= this -> SUNNY
    private static final float N_SCATTERED_EDGE = 0.53f; // n in [this, SUNNY_EDGE) -> SCATTERED
    private static final float N_PLEASANT_EDGE  = 0.33f; // n in [this, SCATTERED_EDGE) -> PLEASANT
    private static final float N_MOSTLY_EDGE    = 0.244f; // n in [this, PLEASANT_EDGE) -> MOSTLY_CLOUDY
    // n < N_MOSTLY_EDGE -> OVERCAST

    /** Coverage/wind/turbulence knots, one per boundary above plus both ends
     *  of the n domain, so the continuous values interpolate smoothly right
     *  across each named boundary instead of snapping. Order matches
     *  {@code {1.00, N_SUNNY_EDGE, N_SCATTERED_EDGE, N_PLEASANT_EDGE,
     *  N_MOSTLY_EDGE, 0.00}}. SUNNY is pushed noticeably clearer than before
     *  (it was reading as too cloudy), and SCATTERED sits entirely above
     *  PLEASANT's own range so it always reads as lighter cloud than
     *  Partly Cloudy. */
    private static final float[] COVERAGE_KNOTS   = { 0.94f, 0.85f, 0.68f, 0.42f, 0.28f, 0.10f };
    private static final float[] WIND_SPEED_KNOTS = { 0.35f, 0.45f, 0.60f, 0.85f, 1.25f, 1.75f };
    private static final float[] TURBULENCE_KNOTS = { 0.00f, 0.02f, 0.06f, 0.18f, 0.50f, 0.90f };

    /** STORM's own coverage/wind/turbulence, blended in by stormFactor on
     *  top of whichever base condition is currently active. */
    private static final float STORM_COVERAGE   = 0.06f;
    private static final float STORM_WIND_SPEED = 2.30f;
    private static final float STORM_TURBULENCE = 1.00f;

    /** STORM can only start escalating once `n` has dropped anywhere within
     *  the OVERCAST zone -- storms are a genuine weather system intensifying
     *  out of already-heavy cloud, not an independent random event that can
     *  strike a clear or partly-cloudy sky. */
    private static final float STORM_ARM_N = N_MOSTLY_EDGE;
    /** Period (game days) of the dedicated pulse that decides *whether* an
     *  already-armed (sufficiently overcast) stretch actually escalates into
     *  a storm. Tuned empirically (see WeatherProbe2) so STORM still reads
     *  as an occasional, genuine event rather than every deep-overcast
     *  stretch escalating. */
    private static final float STORM_PULSE_PERIOD = 3.0f;
    private static final float STORM_THRESHOLD_LO = 0.12f;
    private static final float STORM_THRESHOLD_HI = 0.32f;

    // Distinct large odd salts decorrelate the coverage octaves, the storm
    // pulse, and the two wind-phase samples from each other and from index 0,
    // so they don't all land on the same noise cell.
    private static final long SALT_COVERAGE_0 = 0x9E3779B97F4A7C15L;
    private static final long SALT_COVERAGE_1 = 0xC2B2AE3D27D4EB4FL;
    private static final long SALT_COVERAGE_2 = 0x165667B19E3779F9L;
    private static final long SALT_STORM_PULSE  = 0x5DEECE66D2ADEEEDL;
    private static final long SALT_WIND_PHASE_1 = 0x27D4EB2F165667C5L;
    private static final long SALT_WIND_PHASE_2 = 0x94D049BB133111EBL;

    // --- State (recomputed, never hand-mutated) ------------------------------

    /** Current named condition -- nearest bucket to {@link #cloudCoverage}. */
    public static Condition condition = Condition.PLEASANT;

    /** Cloud coverage [0, 1] -- shader noise threshold (higher = fewer clouds). */
    public static float cloudCoverage = COVERAGE_KNOTS[2];

    /** Prevailing wind direction in radians (0 = +X / East). Meanders over days. */
    public static float windAngle = 0.3f;
    /** Wind speed multiplier (1.0 = normal planet-base speed). */
    public static float windSpeed = WIND_SPEED_KNOTS[2];
    /** Atmospheric turbulence [0-1]: scales vertical layer oscillation amplitude. */
    public static float turbulence = TURBULENCE_KNOTS[2];

    /** Storm intensity [0,1] -- 0 across every condition up to and including
     *  OVERCAST, ramping up only within the dedicated STORM band. Lets the
     *  sky shader unlock its darkest, most ominous L1 look exclusively during
     *  genuine storms instead of on any heavily overcast day. */
    public static float stormFactor = 0f;

    // --- Public API ---------------------------------------------------------

    /**
     * Recomputes weather for the current instant.
     *
     * @param totalGameDays whole game days elapsed plus the fractional time
     *                      of day (e.g. {@code dayCount + timeOfDay})
     */
    public static void update(double totalGameDays) {
        recompute(totalGameDays);
    }

    /**
     * Establishes weather for a freshly loaded world, matching its stored day
     * immediately instead of always restarting from a day-zero roll.
     *
     * @param totalGameDays the world's restored dayCount + timeOfDay
     */
    public static void reset(double totalGameDays) {
        recompute(totalGameDays);
        System.out.println("Weather (seed " + World.WORLD_SEED + "): " + condition.label
                + " (coverage=" + String.format("%.2f", cloudCoverage) + ")");
    }

    // --- Internal -------------------------------------------------------------

    /** Three octaves of smooth 1D noise at different day-scale periods: one
     *  slow-moving large weather system, one multi-day front, and one faster
     *  short-term variation -- the same layered-frequency idea as the
     *  shader's fbm(), just over time instead of over XZ. The salts keep the
     *  octaves from all landing on the same noise cell.
     *
     *  Package-private (not private) purely so tuning tools in this package
     *  can sample the raw distribution directly when picking the N_*_EDGE
     *  thresholds above -- this noise's empirical shape (biased by how long
     *  the eased curve lingers near each hashed lattice point, not a simple
     *  "sum of uniforms" bell curve) turned out not to match a naive
     *  analytical guess, so thresholds must be chosen from real samples. */
    static float rawCoverageNoise(double totalGameDays, long seed) {
        float n = 0.55f * noise1D(totalGameDays / 3.1, seed + SALT_COVERAGE_0)
                + 0.30f * noise1D(totalGameDays / 1.1, seed + SALT_COVERAGE_1)
                + 0.17f * noise1D(totalGameDays / 0.4, seed + SALT_COVERAGE_2);
        return clamp01(n);
    }

    private static final float[] N_KNOTS = {
        1.00f, N_SUNNY_EDGE, N_SCATTERED_EDGE, N_PLEASANT_EDGE, N_MOSTLY_EDGE, 0.00f
    };

    private static void recompute(double totalGameDays) {
        long seed = World.WORLD_SEED;
        float n = rawCoverageNoise(totalGameDays, seed);

        float baseCoverage   = interpKnots(N_KNOTS, COVERAGE_KNOTS, n);
        float baseWindSpeed  = interpKnots(N_KNOTS, WIND_SPEED_KNOTS, n);
        float baseTurbulence = interpKnots(N_KNOTS, TURBULENCE_KNOTS, n);
        Condition baseCondition = baseConditionForN(n);

        // STORM is an occasional escalation on top of already-heavy base
        // weather, not a 5th band carved out of the same coverage noise --
        // gating it on a separate, independently periodic pulse keeps its
        // rarity tunable on its own, without perturbing how often
        // SUNNY..OVERCAST occur. sqrt-eased so most of the OVERCAST zone
        // counts as reasonably "armed" rather than only its deepest sliver --
        // a linear ramp made the joint (armed AND pulse-open) requirement
        // almost impossible to satisfy simultaneously, which was why STORM
        // essentially never triggered.
        float overcastness = (float) Math.sqrt(clamp01((STORM_ARM_N - n) / STORM_ARM_N));
        float stormPulse = noise1D(totalGameDays / STORM_PULSE_PERIOD, seed + SALT_STORM_PULSE);
        float stormGate = smoothstep(STORM_THRESHOLD_LO, STORM_THRESHOLD_HI, stormPulse);
        stormFactor = overcastness * stormGate;

        cloudCoverage = mix(baseCoverage, STORM_COVERAGE, stormFactor);
        windSpeed     = mix(baseWindSpeed, STORM_WIND_SPEED, stormFactor);
        turbulence    = mix(baseTurbulence, STORM_TURBULENCE, stormFactor);
        condition     = stormFactor > 0.32f ? Condition.STORM : baseCondition;

        // Wind direction: a slow continuous rotation (full turn every 16 game
        // days) plus two meander harmonics. The phases are seeded so each
        // world's wind still varies, while remaining a pure function of time.
        float phase1 = hashFloat(seed, SALT_WIND_PHASE_1) * (float) (2.0 * Math.PI);
        float phase2 = hashFloat(seed, SALT_WIND_PHASE_2) * (float) (2.0 * Math.PI);
        windAngle = (float) (
                totalGameDays * (2.0 * Math.PI / 16.0)
                + Math.sin(totalGameDays * 2.0 * Math.PI / 4.8 + phase1) * 0.50
                + Math.sin(totalGameDays * 2.0 * Math.PI / 1.9 + phase2) * 0.15);
    }

    /** Named zone this `n` value falls in, per the N_*_EDGE thresholds. */
    private static Condition baseConditionForN(float n) {
        if (n >= N_SUNNY_EDGE) return Condition.SUNNY;
        if (n >= N_SCATTERED_EDGE) return Condition.SCATTERED;
        if (n >= N_PLEASANT_EDGE) return Condition.PLEASANT;
        if (n >= N_MOSTLY_EDGE) return Condition.MOSTLY_CLOUDY;
        return Condition.OVERCAST;
    }

    /** Classic smoothstep, hand-rolled since GLSL's isn't available here. */
    private static float smoothstep(float lo, float hi, float x) {
        float t = clamp01((x - lo) / Math.max(hi - lo, 1e-6f));
        return t * t * (3f - 2f * t);
    }

    /** Piecewise-linear interpolation across knots at arbitrary, possibly
     *  unevenly-spaced x-positions listed in descending order (nKnots[0] is
     *  the largest). Used to keep coverage/wind/turbulence continuous across
     *  the non-uniformly-sized named zones above. */
    private static float interpKnots(float[] nKnots, float[] values, float n) {
        int i = 0;
        while (i < nKnots.length - 2 && n < nKnots[i + 1]) i++;
        float n0 = nKnots[i], n1 = nKnots[i + 1];
        float t = (n0 - n1) > 1e-6f ? (n0 - n) / (n0 - n1) : 0f;
        t = clamp01(t);
        return values[i] + (values[i + 1] - values[i]) * t;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Smoothly interpolated 1D value noise: hashed integer lattice points,
     *  eased between with a smoothstep so the curve (and everything derived
     *  from it) is continuous rather than snapping cell to cell. */
    private static float noise1D(double t, long seed) {
        long i0 = (long) Math.floor(t);
        long i1 = i0 + 1;
        float f = (float) (t - i0);
        float v0 = hashFloat(seed, i0);
        float v1 = hashFloat(seed, i1);
        float s = f * f * (3f - 2f * f);
        return v0 + (v1 - v0) * s;
    }

    /** Deterministic hash of (seed, index) to a float in [0, 1). Stateless and
     *  position-independent, unlike java.util.Random, so any (seed, index)
     *  pair can be queried directly without replaying a sequence. */
    private static float hashFloat(long seed, long index) {
        long h = seed + index * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h =  h ^ (h >>> 31);
        return (h >>> 40) / (float) (1L << 24);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
