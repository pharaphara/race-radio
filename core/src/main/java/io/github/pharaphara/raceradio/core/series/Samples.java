package io.github.pharaphara.raceradio.core.series;

import java.util.Arrays;
import java.util.Objects;

/**
 * One value per recorded second, in recording order.
 *
 * <p>This is the shape Intervals.icu delivers streams in: paused time is simply absent, so index
 * {@code i} is the i-th moving second, not the i-th second since the start. A missing reading (a
 * dropped heart-rate packet, a power meter waking up) is {@link Double#NaN} and every statistic
 * here skips it rather than treating it as zero.
 *
 * <p>Immutable: the constructor copies, and every accessor that returns an array copies too.
 */
public final class Samples {

    private final double[] values;

    private Samples(double[] values) {
        this.values = values;
    }

    public static Samples of(double... values) {
        return new Samples(Objects.requireNonNull(values, "values").clone());
    }

    /** A constant series, mostly useful in tests and for building synthetic workouts. */
    public static Samples constant(double value, int seconds) {
        double[] v = new double[seconds];
        Arrays.fill(v, value);
        return new Samples(v);
    }

    /** Joins series end to end, which is how a synthetic interval session is built. */
    public static Samples concat(Samples... parts) {
        int length = 0;
        for (Samples p : parts) {
            length += p.values.length;
        }
        double[] v = new double[length];
        int at = 0;
        for (Samples p : parts) {
            System.arraycopy(p.values, 0, v, at, p.values.length);
            at += p.values.length;
        }
        return new Samples(v);
    }

    public int length() {
        return values.length;
    }

    public double at(int second) {
        return values[second];
    }

    public double[] toArray() {
        return values.clone();
    }

    public Samples slice(int fromInclusive, int toExclusive) {
        return new Samples(Arrays.copyOfRange(values, fromInclusive, toExclusive));
    }

    /** Mean of the present readings, or NaN when there are none. */
    public double mean() {
        double sum = 0;
        int n = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /**
     * Trailing rolling mean over {@code window} seconds. The first {@code window - 1} positions
     * average what is available so far, so the output has the same length as the input and stays
     * aligned with it. Missing readings are skipped, not counted as zero.
     */
    public Samples rollingMean(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least one second");
        }
        double[] out = new double[values.length];
        double sum = 0;
        int present = 0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) {
                sum += values[i];
                present++;
            }
            int leaving = i - window;
            if (leaving >= 0 && !Double.isNaN(values[leaving])) {
                sum -= values[leaving];
                present--;
            }
            out[i] = present == 0 ? Double.NaN : sum / present;
        }
        return new Samples(out);
    }

    /** Number of seconds that carry a reading. */
    public int presentCount() {
        int n = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        return n;
    }
}
