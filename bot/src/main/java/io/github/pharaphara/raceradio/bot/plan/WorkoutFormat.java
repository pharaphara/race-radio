package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A workout as an athlete reads it, in French: one line per section, repetitions in brackets, and
 * the time a distance step will take at its pace, computed here rather than left to the reader
 * ("1 km à 4:10-4:20/km ≈ 4 min 15").
 */
public final class WorkoutFormat {

    private static final Map<String, String> SECTIONS = Map.of("Warmup", "Échauffement", "Main Set", "Corps de séance",
            "Cooldown", "Retour au calme", "Drills", "Éducatifs");

    private WorkoutFormat() {
    }

    public static List<String> lines(Workout w, Thresholds t) {
        List<String> out = new ArrayList<>();
        for (Section s : w.sections()) {
            List<String> steps = s.steps().stream().map(st -> step(st, t)).toList();
            String body = String.join(" puis ", steps);
            String label = SECTIONS.getOrDefault(s.title(), s.title().isBlank() ? "Bloc" : s.title());
            out.add(label + " · " + (s.repeats() > 1 ? s.repeats() + " × (" + body + ")" : body));
        }
        return out;
    }

    /** Total planned time, distances converted at their pace. */
    public static String total(Workout w, Thresholds t) {
        return duration(Draft.seconds(w, t));
    }

    public static String sport(String type) {
        return switch (type) {
            case "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> "vélo";
            case "Swim", "OpenWaterSwim" -> "natation";
            case "TrailRun" -> "trail";
            default -> "course";
        };
    }

    static String step(Step s, Thresholds t) {
        StringBuilder b = new StringBuilder();
        switch (s.length()) {
            case Length.Time time -> b.append(duration(time.seconds()));
            case Length.Distance d -> {
                b.append(distance(d.metres()));
                if (s.target() instanceof Target.RunPace || s.target() instanceof Target.SwimPace) {
                    b.append(" ≈ ").append(duration(Draft.seconds(s, t)));
                }
            }
        }
        b.append(" à ").append(target(s.target()));
        if (s.cadence() != null) {
            b.append(", ").append(s.cadence()).append(" rpm");
        }
        if (!s.cue().isBlank()) {
            b.append(" « ").append(s.cue()).append(" »");
        }
        return b.toString();
    }

    static String target(Target t) {
        return switch (t) {
            case Target.Power p -> p.low() == p.high() ? p.low() + " W" : p.low() + "-" + p.high() + " W";
            case Target.PowerZone z -> "zone " + z.zone();
            case Target.HeartRateZone z -> "FC zone " + z.zone();
            case Target.RunPace p -> pace(p.fast()) + (p.fast() == p.slow() ? "" : "-" + pace(p.slow())) + "/km";
            case Target.SwimPace p -> pace(p.fast()) + (p.fast() == p.slow() ? "" : "-" + pace(p.slow())) + "/100 m";
        };
    }

    static String duration(int seconds) {
        int h = seconds / 3600;
        int m = seconds % 3600 / 60;
        int s = seconds % 60;
        if (h > 0) {
            return h + " h " + String.format("%02d", m);
        }
        if (m == 0) {
            return s + " s";
        }
        return s == 0 ? m + " min" : m + " min " + String.format("%02d", s);
    }

    static String distance(int metres) {
        if (metres < 1000) {
            return metres + " m";
        }
        double km = metres / 1000.0;
        return (km == Math.rint(km) ? String.valueOf((int) km) : String.format(Locale.FRANCE, "%.1f", km)) + " km";
    }

    private static String pace(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
