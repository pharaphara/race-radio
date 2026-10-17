package io.github.pharaphara.raceradio.icu.workout;

import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the calendar's own interpretation of a workout (its {@code workout_doc}) and compares it
 * with what the coach meant. The workout document is what the watch receives, so agreement here is
 * what "published" means.
 */
public final class WorkoutDoc {

    /** One step as the calendar understood it, repeats expanded. */
    public record DocStep(Integer seconds, Integer metres, String units, Double low, Double high) {
    }

    private WorkoutDoc() {
    }

    public static List<DocStep> flatten(JsonNode doc) {
        List<DocStep> out = new ArrayList<>();
        if (doc == null || doc.isNull() || doc.isMissingNode()) {
            return out;
        }
        walk(doc.path("steps"), out);
        return out;
    }

    private static void walk(JsonNode steps, List<DocStep> out) {
        for (JsonNode s : steps) {
            JsonNode nested = s.path("steps");
            if (nested.isArray() && !nested.isEmpty()) {
                int reps = s.path("reps").isNumber() ? s.path("reps").asInt() : 1;
                for (int r = 0; r < reps; r++) {
                    walk(nested, out);
                }
                continue;
            }
            JsonNode target = first(s, "power", "hr", "pace");
            String units = target == null ? null : target.path("units").asString(null);
            Double low = null;
            Double high = null;
            if (target != null) {
                if (target.has("value")) {
                    low = high = target.path("value").asDouble();
                } else if (target.has("start") && target.has("end")) {
                    double a = target.path("start").asDouble();
                    double b = target.path("end").asDouble();
                    low = Math.min(a, b);
                    high = Math.max(a, b);
                }
            }
            Integer seconds = s.path("duration").isNumber() ? s.path("duration").asInt() : null;
            Integer metres = s.path("distance").isNumber() ? (int) Math.round(s.path("distance").asDouble()) : null;
            out.add(new DocStep(seconds, metres, units, low, high));
        }
    }

    private static JsonNode first(JsonNode s, String... keys) {
        for (String k : keys) {
            JsonNode n = s.path(k);
            if (n.isObject()) {
                return n;
            }
        }
        return null;
    }

    /** Differences between intent and interpretation, step by step. Empty when they agree. */
    public static List<String> disagreements(Workout intent, JsonNode doc) {
        List<Step> want = intent.flatten();
        List<DocStep> got = flatten(doc);
        List<String> d = new ArrayList<>();
        if (got.isEmpty()) {
            d.add("the calendar did not understand the workout at all");
            return d;
        }
        if (want.size() != got.size()) {
            d.add("expected " + want.size() + " steps, the calendar read " + got.size());
            return d;
        }
        for (int i = 0; i < want.size(); i++) {
            String why = compare(want.get(i), got.get(i));
            if (why != null) {
                d.add("step " + (i + 1) + ": " + why);
            }
        }
        return d;
    }

    private static String compare(Step want, DocStep got) {
        switch (want.length()) {
            case Length.Time t when got.seconds() == null || Math.abs(got.seconds() - t.seconds()) > 1 ->
                    { return "expected " + t.seconds() + " s, read " + got.seconds(); }
            case Length.Distance m when got.metres() == null || Math.abs(got.metres() - m.metres()) > 1 ->
                    { return "expected " + m.metres() + " m, read " + got.metres(); }
            default -> { }
        }
        Expected e = expected(want.target());
        if (!e.units().equals(got.units())) {
            return "expected a " + e.units() + " target, read " + got.units();
        }
        if (got.low() == null || Math.abs(got.low() - e.low()) > 0.5 || Math.abs(got.high() - e.high()) > 0.5) {
            return "expected " + e.low() + "-" + e.high() + " " + e.units() + ", read " + got.low() + "-" + got.high();
        }
        return null;
    }

    private record Expected(String units, double low, double high) {
    }

    private static Expected expected(Target t) {
        return switch (t) {
            case Target.Power p -> new Expected("w", p.low(), p.high());
            case Target.PowerZone z -> new Expected("power_zone", z.zone(), z.zone());
            case Target.HeartRateZone z -> new Expected("hr_zone", z.zone(), z.zone());
            case Target.RunPace r -> new Expected("secs/km", r.fast(), r.slow());
            case Target.SwimPace s -> new Expected("secs/100m", s.fast(), s.slow());
        };
    }
}
