package io.github.pharaphara.raceradio.core.power;

import io.github.pharaphara.raceradio.core.series.Samples;

/**
 * Session-level power metrics.
 *
 * <p>The weighted average power follows the published algorithm: smooth with a 30-second rolling
 * average, raise each value to the fourth power, average, take the fourth root. The fourth power
 * reflects that physiological cost rises much faster than linearly with output, so a surging ride
 * scores higher than a steady one at the same mean.
 */
public final class PowerAnalysis {

    /** Smoothing window of the weighted average, fixed by the published method. */
    public static final int SMOOTHING_SECONDS = 30;

    private PowerAnalysis() {
    }

    public static PowerSummary summarise(Samples power, double thresholdPower) {
        if (thresholdPower <= 0) {
            throw new IllegalArgumentException("threshold power must be positive");
        }
        int seconds = power.presentCount();
        if (seconds == 0) {
            return new PowerSummary(0, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN);
        }
        double average = power.mean();
        double weighted = weightedAveragePower(power);
        double intensity = weighted / thresholdPower;
        double load = seconds * weighted * intensity / (thresholdPower * 3600.0) * 100.0;
        double variability = average > 0 ? weighted / average : Double.NaN;
        return new PowerSummary(seconds, average, weighted, intensity, load, variability);
    }

    public static double weightedAveragePower(Samples power) {
        Samples smoothed = power.rollingMean(SMOOTHING_SECONDS);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < smoothed.length(); i++) {
            double v = smoothed.at(i);
            if (!Double.isNaN(v)) {
                sum += v * v * v * v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.pow(sum / n, 0.25);
    }
}
