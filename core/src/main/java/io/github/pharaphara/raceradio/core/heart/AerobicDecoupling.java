package io.github.pharaphara.raceradio.core.heart;

import io.github.pharaphara.raceradio.core.series.Samples;

/**
 * How much heart rate drifts upward for the same output over a steady effort.
 *
 * <p>The session is split into two halves; in each, efficiency is output divided by heart rate.
 * Decoupling is the relative loss of efficiency from the first half to the second. Under about
 * five percent on a long steady effort is the usual sign of a solid aerobic base. The figure is
 * only meaningful on steady efforts: on an interval session it measures the structure of the
 * workout, not the athlete, and the coach does not report it there.
 *
 * <p>Intervals.icu computes its own figure; this one exists so the coach can evaluate any
 * portion of a session, such as a single long interval, and so the behaviour is under test.
 */
public final class AerobicDecoupling {

    private AerobicDecoupling() {
    }

    /**
     * @param output     power in watts, or speed in metres per second
     * @param heartRate  beats per minute, aligned with {@code output}
     * @return decoupling as a fraction (0.05 for five percent); positive means heart rate drifted up
     */
    public static double of(Samples output, Samples heartRate) {
        if (output.length() != heartRate.length()) {
            throw new IllegalArgumentException("output and heart rate must cover the same seconds");
        }
        int half = output.length() / 2;
        double first = efficiency(output.slice(0, half), heartRate.slice(0, half));
        double second = efficiency(output.slice(half, output.length()), heartRate.slice(half, heartRate.length()));
        return (first - second) / first;
    }

    private static double efficiency(Samples output, Samples heartRate) {
        return output.mean() / heartRate.mean();
    }
}
