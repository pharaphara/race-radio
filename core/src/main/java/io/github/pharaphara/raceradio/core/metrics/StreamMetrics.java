package io.github.pharaphara.raceradio.core.metrics;

import io.github.pharaphara.raceradio.core.heart.AerobicDecoupling;
import io.github.pharaphara.raceradio.core.series.HeartRate;
import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.core.swim.StrokeEfficiency;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The few per-session figures Intervals.icu does not already provide in an activity's summary,
 * computed once from the streams and kept. Everything the summary carries (efficiency factor,
 * cycling decoupling, average stride, estimated threshold power...) is read from the archived
 * summary instead, never recomputed.
 *
 * <p>Streams are taken as one reading per second, as Intervals.icu exports them. Each figure is left
 * out when the session does not hold enough of the signal it needs, rather than estimated.
 */
public final class StreamMetrics {

    /** Bumped whenever a figure is added or its definition changes, so archived sessions are recomputed locally. */
    public static final int VERSION = 3;

    /** Best mean power windows, in seconds, by key. */
    public static final Map<String, Integer> POWER_WINDOWS = orderedOf(
            "best_power_5s", 5, "best_power_1min", 60, "best_power_5min", 300, "best_power_20min", 1200, "best_power_60min", 3600);
    /** Best time windows over a distance, in metres, by key. */
    public static final Map<String, Integer> RUN_DISTANCES = orderedOf("best_time_1km", 1000, "best_time_5km", 5000);

    /** Endurance power band as a share of threshold power, for heart rate at a fixed effort. */
    static final double ENDURANCE_LOW = 0.65;
    static final double ENDURANCE_HIGH = 0.75;
    /**
     * Easy running band as a share of threshold speed, for stride and cadence at a fixed pace. Wide
     * enough to hold an ordinary easy run: 75 to 85 % left only three sessions out of a year.
     */
    static final double EASY_LOW = 0.72;
    static final double EASY_HIGH = 0.90;
    static final int MIN_EASY_SECONDS = 300;
    /** Heart rate lags at the start: the first minutes never count towards heart rate at a fixed effort. */
    static final int WARM_UP_SECONDS = 600;
    static final int MIN_BAND_SECONDS = 600;
    static final int MIN_DECOUPLING_SECONDS = 2400;

    private static final Set<String> BIKE = Set.of("Ride", "VirtualRide", "GravelRide", "MountainBikeRide");
    private static final Set<String> RUN = Set.of("Run", "TrailRun", "VirtualRun", "Treadmill");
    private static final Set<String> SWIM = Set.of("Swim", "OpenWaterSwim");

    private StreamMetrics() {
    }

    /**
     * @param sport            Intervals.icu activity type
     * @param streams          column name to samples, as in the streams export
     * @param thresholdPower   the threshold power the session was ridden with, or null
     * @param thresholdSpeed   threshold running speed in metres per second, or null
     */
    public static Map<String, Double> compute(String sport, Function<String, Optional<Samples>> streams,
                                              Integer thresholdPower, Double thresholdSpeed) {
        Map<String, Double> out = new LinkedHashMap<>();
        Optional<Samples> hr = streams.apply("heartrate").map(HeartRate::clean);
        // Held thirty seconds: a strap glitch of a few seconds cannot make a maximum.
        hr.ifPresent(h -> put(out, "hr_max_30s", HeartRate.maxHeld(h, 30)));
        if (BIKE.contains(sport)) {
            streams.apply("watts").ifPresent(w -> {
                POWER_WINDOWS.forEach((key, window) -> put(out, key, bestMean(w, window)));
                if (thresholdPower != null && hr.isPresent()) {
                    put(out, "hr_at_endurance_power", meanWhere(hr.get(), w.rollingMean(30),
                            ENDURANCE_LOW * thresholdPower, ENDURANCE_HIGH * thresholdPower));
                }
            });
        } else if (RUN.contains(sport)) {
            Optional<Samples> speed = streams.apply("velocity_smooth");
            streams.apply("distance").ifPresent(d -> RUN_DISTANCES.forEach((key, metres) -> put(out, key, bestTime(d, metres))));
            if (speed.isPresent() && hr.isPresent() && speed.get().length() >= MIN_DECOUPLING_SECONDS) {
                double dec = AerobicDecoupling.of(speed.get(), hr.get());
                put(out, "run_decoupling_percent", Double.isNaN(dec) ? Double.NaN : 100 * dec);
            }
            if (speed.isPresent() && thresholdSpeed != null) {
                Samples smooth = speed.get().rollingMean(30);
                double lo = EASY_LOW * thresholdSpeed;
                double hi = EASY_HIGH * thresholdSpeed;
                // Stride arrives in millimetres, cadence per leg: reported in metres and steps per minute.
                streams.apply("step_length").ifPresent(s -> put(out, "stride_at_easy_pace", meanWhere(s, smooth, lo, hi, MIN_EASY_SECONDS) / 1000));
                streams.apply("cadence").ifPresent(c -> put(out, "cadence_at_easy_pace", 2 * meanWhere(c, smooth, lo, hi, MIN_EASY_SECONDS)));
            }
        } else if (SWIM.contains(sport)) {
            Optional<Samples> speed = streams.apply("velocity_smooth");
            Optional<Samples> rate = streams.apply("cadence");
            if (speed.isPresent() && rate.isPresent()) {
                put(out, "distance_per_stroke", StrokeEfficiency.distancePerStroke(speed.get(), rate.get()));
            }
        }
        return out;
    }

    /** Highest mean over any {@code window} consecutive seconds; NaN when the session is shorter. */
    static double bestMean(Samples s, int window) {
        if (s.length() < window) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < window; i++) {
            sum += value(s, i);
        }
        double best = sum;
        for (int i = window; i < s.length(); i++) {
            sum += value(s, i) - value(s, i - window);
            best = Math.max(best, sum);
        }
        return best / window;
    }

    /**
     * Fewest seconds to cover {@code metres}, from a cumulative distance whose origin does not
     * matter (the export may start far from zero). NaN when the session is shorter.
     */
    static double bestTime(Samples distance, int metres) {
        double[] d = distance.toArray();
        double best = Double.NaN;
        int from = 0;
        for (int to = 0; to < d.length; to++) {
            if (Double.isNaN(d[to])) {
                continue;
            }
            while (from < to && (Double.isNaN(d[from]) || d[to] - d[from] >= metres)) {
                if (!Double.isNaN(d[from]) && d[to] - d[from] >= metres) {
                    double seconds = to - from;
                    best = Double.isNaN(best) ? seconds : Math.min(best, seconds);
                }
                from++;
            }
        }
        return best;
    }

    /** Mean of {@code values} over the seconds where {@code band} lies within [low, high], after the warm-up. */
    static double meanWhere(Samples values, Samples band, double low, double high) {
        return meanWhere(values, band, low, high, MIN_BAND_SECONDS);
    }

    /** Same, with its own minimum of seconds in the band. */
    static double meanWhere(Samples values, Samples band, double low, double high, int minSeconds) {
        int n = Math.min(values.length(), band.length());
        double sum = 0;
        int count = 0;
        for (int i = WARM_UP_SECONDS; i < n; i++) {
            double b = band.at(i);
            double v = values.at(i);
            if (b >= low && b <= high && v > 0) {
                sum += v;
                count++;
            }
        }
        return count >= minSeconds ? sum / count : Double.NaN;
    }

    private static double value(Samples s, int i) {
        double v = s.at(i);
        return Double.isNaN(v) ? 0 : v;
    }

    private static void put(Map<String, Double> out, String key, double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            out.put(key, value);
        }
    }

    private static Map<String, Integer> orderedOf(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
