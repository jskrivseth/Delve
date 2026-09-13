package delve.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Threshold behaviour of the cloud TAA history-rejection score
 * ({@link Renderer#taaMotionScore}): slow motion keeps the history (that's
 * the whole point), motion past the trust limits zeroes it out (that's what
 * stops ghosting during sweeps, strafes and climbs).
 */
public class CloudTaaMotionTest {

    @Test
    public void stationaryCameraFullyTrustsHistory() {
        assertEquals(0.0f, Renderer.taaMotionScore(0.0, 0.0), 1e-6);
    }

    @Test
    public void gentleMotionKeepsHistoryMostlyWeighted() {
        // ~10 deg/s yaw and a walk-speed slide: history must stay in play.
        float s = Renderer.taaMotionScore(Math.toRadians(10.0), 8.0);
        assertTrue(s > 0.0f && s < 0.25f, "gentle motion scored " + s);
    }

    @Test
    public void fullTrustLimitsRejectHistoryEntirely() {
        assertTrue(Renderer.taaMotionScore(Math.toRadians(160.0), 0.0) >= 0.99f);
        assertTrue(Renderer.taaMotionScore(0.0, 350.0) >= 0.99f);
    }

    @Test
    public void scoreMonotonicInBothAxes() {
        float a = Renderer.taaMotionScore(0.5, 0.0);
        float b = Renderer.taaMotionScore(1.0, 0.0);
        float c = Renderer.taaMotionScore(1.0, 150.0);
        assertTrue(a < b && b <= c);
    }

    @Test
    public void scoreNeverExceedsOne() {
        assertTrue(Renderer.taaMotionScore(1e6, 1e6) <= 1.0f);
    }
}
