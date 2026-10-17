package io.github.pharaphara.raceradio.icu.workout;

import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link Workout} in Intervals.icu's workout text, the format the calendar parses into
 * a structured workout and pushes to the watch and to the bike computer.
 *
 * <p>Only a strict subset of the syntax is ever produced: forms observed in workouts the calendar
 * accepted. Free text (section titles and cues shown on the watch) is the only place a mistake can
 * slip in, because the parser reads durations and targets wherever it finds them: a cue such as
 * "3 x 5 min" would be read as instructions. {@link #problems(Workout)} therefore forbids digits
 * and target keywords in free text, and rejects targets the sport cannot measure.
 *
 * <p>Passing this check is necessary, not sufficient. The real test happens after publication,
 * when the calendar's own reading is compared with the intent; see {@code IntervalsWriter}.
 */
public final class WorkoutText {

    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern RESERVED = Pattern.compile(
            "(?i)\\b(hr|pace|rpm|ramp|mtr|km|lthr|ftp|z\\w?)\\b|[%:/]|^\\s*-");
    private static final int MAX_FREE_TEXT = 40;

    private static final Set<String> BIKE = Set.of("Ride", "VirtualRide", "GravelRide", "MountainBikeRide");
    private static final Set<String> RUN = Set.of("Run", "TrailRun", "VirtualRun");
    private static final Set<String> SWIM = Set.of("Swim", "OpenWaterSwim");

    private WorkoutText() {
    }

    /** Text ready for the calendar. Refuses to render a workout with problems. */
    public static String render(Workout w) {
        List<String> problems = problems(w);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("workout cannot be published: " + String.join("; ", problems));
        }
        StringBuilder b = new StringBuilder();
        for (Section s : w.sections()) {
            if (!b.isEmpty()) {
                b.append("\n\n");
            }
            b.append(s.title());
            if (s.repeats() > 1) {
                b.append(' ').append(s.repeats()).append('x');
            }
            for (Step step : s.steps()) {
                b.append("\n- ").append(length(step.length())).append(' ').append(target(step.target()));
                if (step.cadence() != null) {
                    b.append(' ').append(step.cadence()).append("rpm");
                }
                if (!step.cue().isEmpty()) {
                    b.append(' ').append(step.cue());
                }
            }
        }
        return b.toString();
    }

    /** Everything that would make the calendar misread or reject the workout. Empty when fine. */
    public static List<String> problems(Workout w) {
        List<String> p = new ArrayList<>();
        Set<Class<? extends Target>> allowed = allowedTargets(w.sport());
        if (allowed.isEmpty()) {
            p.add("unsupported sport " + w.sport());
        }
        for (Section s : w.sections()) {
            freeText("section title", s.title(), p, true);
            for (Step step : s.steps()) {
                freeText("cue", step.cue(), p, false);
                if (!allowed.isEmpty() && !allowed.contains(step.target().getClass())) {
                    p.add(step.target().getClass().getSimpleName() + " is not prescribed for " + w.sport());
                }
                if (step.cadence() != null && !BIKE.contains(w.sport())) {
                    p.add("cadence targets are for cycling");
                }
            }
        }
        return p;
    }

    static String length(Length l) {
        return switch (l) {
            case Length.Time t -> duration(t.seconds());
            case Length.Distance d -> d.metres() + "mtr";
        };
    }

    static String duration(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return s == 0 ? m + "m" : m + "m" + s + "s";
    }

    static String target(Target t) {
        return switch (t) {
            case Target.Power p -> p.low() == p.high() ? p.low() + "w" : p.low() + "-" + p.high() + "w";
            case Target.PowerZone z -> "Z" + z.zone();
            case Target.HeartRateZone z -> "Z" + z.zone() + " HR";
            case Target.RunPace r -> pace(r.fast(), r.slow()) + "/km Pace";
            case Target.SwimPace s -> pace(s.fast(), s.slow()) + "/100m Pace";
        };
    }

    private static String pace(int fast, int slow) {
        return fast == slow ? mmss(fast) : mmss(fast) + "-" + mmss(slow);
    }

    private static String mmss(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private static void freeText(String what, String text, List<String> p, boolean required) {
        if (text.isEmpty()) {
            if (required) {
                p.add(what + " is empty");
            }
            return;
        }
        if (DIGIT.matcher(text).find() || RESERVED.matcher(text).find()) {
            p.add(what + " \"" + text + "\" contains a number or a keyword the calendar would read as an instruction");
        }
        if (text.length() > MAX_FREE_TEXT) {
            p.add(what + " \"" + text + "\" is longer than " + MAX_FREE_TEXT + " characters");
        }
    }

    private static Set<Class<? extends Target>> allowedTargets(String sport) {
        if (BIKE.contains(sport)) {
            return Set.of(Target.Power.class, Target.PowerZone.class, Target.HeartRateZone.class);
        }
        if (RUN.contains(sport)) {
            return Set.of(Target.RunPace.class, Target.HeartRateZone.class);
        }
        if (SWIM.contains(sport)) {
            return Set.of(Target.SwimPace.class);
        }
        return Set.of();
    }
}
