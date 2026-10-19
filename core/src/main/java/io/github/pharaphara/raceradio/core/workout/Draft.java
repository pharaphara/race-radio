package io.github.pharaphara.raceradio.core.workout;

import io.github.pharaphara.raceradio.core.plan.Thresholds;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A workout as someone asked for it, before it meets the athlete's numbers: intensities may be a
 * share of threshold power or an offset from threshold pace. {@link #build(Thresholds)} does the
 * arithmetic and refuses anything outside sane bounds, so whoever wrote the draft (a person, or a
 * model translating a request) never computes a watt or a pace.
 *
 * @param sport  an Intervals.icu activity type: Ride, VirtualRide, Run, TrailRun, Swim, ...
 */
public record Draft(String sport, String name, List<DraftSection> sections) {

    /** How an intensity is expressed in a draft. */
    public enum Kind {
        /** Watts, absolute. */
        WATTS,
        /** Percent of threshold power. */
        THRESHOLD_POWER_PERCENT,
        /** Power zone number. */
        POWER_ZONE,
        /** Heart-rate zone number. */
        HEART_RATE_ZONE,
        /** Seconds per kilometre on foot, or per 100 m in the water, absolute. */
        PACE,
        /** Seconds slower than threshold pace (or than CSS in the water); negative is faster. */
        THRESHOLD_PACE_OFFSET
    }

    /**
     * One step. Exactly one of {@code seconds} and {@code metres} is set. For a range, {@code low}
     * is the easier end in watts and the faster end in pace; for a single value, {@code high} may be null.
     */
    public record DraftStep(Integer seconds, Integer metres, Kind kind, double low, Double high, Integer cadence, String cue) {
    }

    public record DraftSection(String title, int repeats, List<DraftStep> steps) {
    }

    /** Longest workout accepted in one go. */
    public static final int MAX_TOTAL_SECONDS = 6 * 3600;
    /** Highest power accepted, as a share of threshold power. */
    public static final double MAX_POWER_SHARE = 2.0;

    private static final Set<String> BIKE = Set.of("Ride", "VirtualRide", "GravelRide", "MountainBikeRide");
    private static final Set<String> RUN = Set.of("Run", "TrailRun", "VirtualRun");
    private static final Set<String> SWIM = Set.of("Swim", "OpenWaterSwim");

    /**
     * The workout with the athlete's numbers in it.
     *
     * @throws IllegalArgumentException  naming the first problem: a threshold the draft needs but the
     *                                   athlete lacks, a target the sport cannot measure, an absurd value
     */
    public Workout build(Thresholds t) {
        if (!BIKE.contains(sport) && !RUN.contains(sport) && !SWIM.contains(sport)) {
            throw new IllegalArgumentException("sport not supported: " + sport);
        }
        if (sections == null || sections.isEmpty()) {
            throw new IllegalArgumentException("the draft has no step");
        }
        List<Section> out = new ArrayList<>();
        long total = 0;
        for (DraftSection s : sections) {
            if (s.repeats() < 1 || s.repeats() > 30) {
                throw new IllegalArgumentException("repeats out of range: " + s.repeats());
            }
            List<Step> steps = new ArrayList<>();
            for (DraftStep d : s.steps() == null ? List.<DraftStep>of() : s.steps()) {
                Step step = new Step(length(d), target(d, t), cadence(d), d.cue());
                steps.add(step);
                total += (long) s.repeats() * seconds(step, t);
            }
            out.add(new Section(s.title(), s.repeats(), steps));
        }
        if (total > MAX_TOTAL_SECONDS) {
            throw new IllegalArgumentException("longer than six hours");
        }
        return new Workout(sport, name == null || name.isBlank() ? "Séance" : name.strip(), out);
    }

    /** Planned duration, distances converted at the step's target pace. */
    public static int seconds(Workout w, Thresholds t) {
        long total = 0;
        for (Step s : w.flatten()) {
            total += seconds(s, t);
        }
        return (int) total;
    }

    /** How long one step lasts, a distance converted at its own target pace. */
    public static int seconds(Step s, Thresholds t) {
        if (s.length() instanceof Length.Time time) {
            return time.seconds();
        }
        int metres = ((Length.Distance) s.length()).metres();
        return switch (s.target()) {
            case Target.RunPace p -> (int) Math.round(metres / 1000.0 * (p.fast() + p.slow()) / 2);
            case Target.SwimPace p -> (int) Math.round(metres / 100.0 * (p.fast() + p.slow()) / 2);
            default -> t.criticalSwimPace() == null ? 0 : (int) Math.round(metres / 100.0 * t.criticalSwimPace());
        };
    }

    private static Length length(DraftStep d) {
        boolean time = d.seconds() != null;
        boolean distance = d.metres() != null;
        if (time == distance) {
            throw new IllegalArgumentException("a step has a duration or a distance, not both nor neither");
        }
        if (time) {
            if (d.seconds() < 5 || d.seconds() > 4 * 3600) {
                throw new IllegalArgumentException("step duration out of range: " + d.seconds() + " s");
            }
            return new Length.Time(d.seconds());
        }
        if (d.metres() < 25 || d.metres() > 50_000) {
            throw new IllegalArgumentException("step distance out of range: " + d.metres() + " m");
        }
        return new Length.Distance(d.metres());
    }

    private Integer cadence(DraftStep d) {
        if (d.cadence() == null) {
            return null;
        }
        if (!BIKE.contains(sport) || d.cadence() < 40 || d.cadence() > 140) {
            throw new IllegalArgumentException("cadence only on the bike, between 40 and 140 rpm");
        }
        return d.cadence();
    }

    private Target target(DraftStep d, Thresholds t) {
        double low = d.low();
        double high = d.high() == null ? low : d.high();
        if (d.kind() == null) {
            throw new IllegalArgumentException("a step has no intensity");
        }
        return switch (d.kind()) {
            case WATTS -> power(low, high, t);
            case THRESHOLD_POWER_PERCENT -> {
                int ftp = need(t.thresholdPower(), "threshold power");
                yield power(ftp * low / 100, ftp * high / 100, t);
            }
            case POWER_ZONE -> {
                bikeOnly();
                yield new Target.PowerZone(zone(low));
            }
            case HEART_RATE_ZONE -> new Target.HeartRateZone(zone(low));
            case PACE -> pace(low, high, t);
            case THRESHOLD_PACE_OFFSET -> {
                int base = SWIM.contains(sport) ? need(t.criticalSwimPace(), "critical swim speed")
                        : need(t.thresholdPace(), "threshold pace");
                yield pace(base + low, base + high, t);
            }
        };
    }

    private Target power(double low, double high, Thresholds t) {
        bikeOnly();
        int lo = (int) Math.round(Math.min(low, high));
        int hi = (int) Math.round(Math.max(low, high));
        double ceiling = t.thresholdPower() == null ? 1500 : MAX_POWER_SHARE * t.thresholdPower();
        if (lo < 30 || hi > ceiling) {
            throw new IllegalArgumentException("power out of range: " + lo + "-" + hi + " W");
        }
        return new Target.Power(lo, hi);
    }

    private Target pace(double a, double b, Thresholds t) {
        int fast = (int) Math.round(Math.min(a, b));
        int slow = (int) Math.round(Math.max(a, b));
        if (RUN.contains(sport)) {
            if (fast < 150 || slow > 600) {
                throw new IllegalArgumentException("run pace out of range: " + fast + "-" + slow + " s/km");
            }
            return new Target.RunPace(fast, slow);
        }
        if (SWIM.contains(sport)) {
            if (fast < 60 || slow > 300) {
                throw new IllegalArgumentException("swim pace out of range: " + fast + "-" + slow + " s/100 m");
            }
            return new Target.SwimPace(fast, slow);
        }
        throw new IllegalArgumentException("pace targets are for running and swimming");
    }

    private void bikeOnly() {
        if (!BIKE.contains(sport)) {
            throw new IllegalArgumentException("power targets are for the bike");
        }
    }

    private static int zone(double z) {
        int n = (int) Math.round(z);
        if (n < 1 || n > 7) {
            throw new IllegalArgumentException("zone out of range: " + z);
        }
        return n;
    }

    private static int need(Integer value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("no " + what + " in the profile");
        }
        return value;
    }
}
