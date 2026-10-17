package io.github.pharaphara.raceradio.core.swim;

/**
 * Critical swim speed: the swimming counterpart of threshold power, from two time trials.
 *
 * <p>Protocol: warm up, swim 400 m all out, recover about ten minutes, swim 200 m all out. Critical
 * swim speed is the slope between the two efforts, the extra distance divided by the extra time.
 * It strips out the anaerobic contribution that inflates a single short time trial.
 */
public final class CriticalSwimSpeed {

    private CriticalSwimSpeed() {
    }

    /** @return metres per second */
    public static double fromTimeTrials(double seconds400, double seconds200) {
        if (seconds400 <= seconds200 || seconds200 <= 0) {
            throw new IllegalArgumentException("the 400 m must take longer than the 200 m, and both must be positive");
        }
        return (400.0 - 200.0) / (seconds400 - seconds200);
    }

    /** Seconds per 100 m, the way swimmers state a pace. */
    public static double secondsPer100m(double metresPerSecond) {
        return 100.0 / metresPerSecond;
    }
}
