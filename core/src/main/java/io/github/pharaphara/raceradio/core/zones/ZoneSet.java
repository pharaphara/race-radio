package io.github.pharaphara.raceradio.core.zones;

import io.github.pharaphara.raceradio.core.series.Samples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered, gap-free set of zones for one metric, built from one threshold.
 *
 * <p>Factories cover the three sports. Boundaries are conventions of practice (Friel's seven-zone
 * system for heart rate and power, threshold-relative paces for running, offsets from critical
 * swim speed for swimming), not measured physiological breakpoints; the knowledge base labels them
 * as such and the coach does not present them as laboratory values.
 */
public record ZoneSet(String metric, String model, List<Zone> zones) {

    public ZoneSet {
        zones = List.copyOf(zones);
        for (int i = 1; i < zones.size(); i++) {
            if (zones.get(i).lower() != zones.get(i - 1).upper()) {
                throw new IllegalArgumentException("zones must be contiguous at " + zones.get(i).label());
            }
        }
    }

    /** Friel's seven cycling power zones, as fractions of threshold power. */
    public static ZoneSet cyclingPower(double thresholdPower) {
        return fromFractions("power", "friel-7/power", thresholdPower,
                new String[] {"1", "2", "3", "4", "5a", "5b", "5c"},
                new String[] {"Recovery", "Aerobic", "Tempo", "Sub-threshold", "Threshold", "VO2max", "Anaerobic"},
                new double[] {0.0, 0.56, 0.76, 0.91, 1.00, 1.06, 1.21, 10.0});
    }

    /** Friel's seven heart-rate zones, as fractions of lactate threshold heart rate. */
    public static ZoneSet heartRate(double thresholdHeartRate) {
        return fromFractions("heartrate", "friel-7/heartrate", thresholdHeartRate,
                new String[] {"1", "2", "3", "4", "5a", "5b", "5c"},
                new String[] {"Recovery", "Aerobic", "Tempo", "Sub-threshold", "Threshold", "VO2max", "Anaerobic"},
                new double[] {0.0, 0.81, 0.90, 0.94, 1.00, 1.03, 1.06, 2.0});
    }

    /**
     * Running zones from threshold pace, expressed as speed, in the tradition of Daniels'
     * threshold-relative paces. The source tables give bands with gaps between them (easy 50 to 70
     * seconds per kilometre slower than threshold, interval 15 to 20 seconds faster, and so on);
     * zones must tile the whole range, so the boundaries here sit between those bands: slower than
     * +70 s/km, +70 to +50, +50 to +20, +20 to +5, +5 to -5 around threshold, -5 to -20, faster.
     */
    public static ZoneSet runningPace(double thresholdSpeed) {
        double thresholdSecPerKm = 1000.0 / thresholdSpeed;
        double[] offsets = {Double.POSITIVE_INFINITY, 70, 50, 20, 5, -5, -20, Double.NEGATIVE_INFINITY};
        return fromPaceOffsets("pace", "threshold-offsets/run", thresholdSecPerKm, 1000.0, offsets,
                new String[] {"1", "2", "3", "4", "5", "6", "7"},
                new String[] {"Recovery", "Easy", "Steady", "Tempo", "Threshold", "Interval", "Repetition"});
    }

    /**
     * Swimming zones from critical swim speed. The source table gives bands in seconds per 100 m
     * (recovery 15 to 20 slower than critical swim speed, aerobic 8 to 12 slower, tempo 3 to 6
     * slower, threshold at it, VO2max 3 to 5 faster). Zones must tile the whole range, so the
     * boundaries sit between those bands: slower than +15, +15 to +8, +8 to +3, +3 to -2, faster.
     */
    public static ZoneSet swimming(double criticalSwimSpeed) {
        double cssSecPer100 = 100.0 / criticalSwimSpeed;
        double[] offsets = {Double.POSITIVE_INFINITY, 15, 8, 3, -2, Double.NEGATIVE_INFINITY};
        return fromPaceOffsets("pace", "css-offsets/swim", cssSecPer100, 100.0, offsets,
                new String[] {"1", "2", "3", "4", "5"},
                new String[] {"Recovery", "Aerobic", "Tempo", "Threshold", "VO2max"});
    }

    /** Seconds spent in each zone, in zone order. Missing readings are not counted anywhere. */
    public Map<Zone, Integer> timeInZones(Samples values) {
        Map<Zone, Integer> seconds = new LinkedHashMap<>();
        zones.forEach(z -> seconds.put(z, 0));
        for (int i = 0; i < values.length(); i++) {
            double v = values.at(i);
            if (Double.isNaN(v)) {
                continue;
            }
            Zone z = zoneOf(v);
            if (z != null) {
                seconds.merge(z, 1, Integer::sum);
            }
        }
        return seconds;
    }

    public Zone zoneOf(double value) {
        for (Zone z : zones) {
            if (z.contains(value)) {
                return z;
            }
        }
        return value < zones.getFirst().lower() ? zones.getFirst() : zones.getLast();
    }

    private static ZoneSet fromFractions(String metric, String model, double threshold,
                                         String[] labels, String[] names, double[] bounds) {
        List<Zone> zs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            zs.add(new Zone(labels[i], names[i], bounds[i] * threshold, bounds[i + 1] * threshold));
        }
        return new ZoneSet(metric, model, zs);
    }

    /**
     * Builds speed zones from offsets in seconds per {@code distance}. Offsets run from slowest to
     * fastest; infinite offsets stand for the open ends (standing still, and anything faster).
     */
    private static ZoneSet fromPaceOffsets(String metric, String model, double thresholdSeconds,
                                           double distance, double[] offsets, String[] labels, String[] names) {
        double[] speeds = new double[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] == Double.POSITIVE_INFINITY) {
                speeds[i] = 0.0;
            } else if (offsets[i] == Double.NEGATIVE_INFINITY) {
                speeds[i] = 100.0;
            } else {
                speeds[i] = distance / (thresholdSeconds + offsets[i]);
            }
        }
        List<Zone> zs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            zs.add(new Zone(labels[i], names[i], speeds[i], speeds[i + 1]));
        }
        return new ZoneSet(metric, model, zs);
    }
}
