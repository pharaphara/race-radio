package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.core.intervals.PlannedStep;
import io.github.pharaphara.raceradio.core.zones.Zone;
import io.github.pharaphara.raceradio.core.zones.ZoneSet;
import io.github.pharaphara.raceradio.icu.model.SportSettings;
import io.github.pharaphara.raceradio.icu.workout.WorkoutDoc;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a planned workout, as the calendar understood it, into the work steps a session is
 * compared with, in the unit of its effort signal. A step is work when its target sits above the
 * endurance plateau, which is the same rule the detector applies to what was done; warm-ups,
 * recoveries and cool-downs fall out of the comparison by themselves.
 *
 * <p>A target the signal cannot express (a heart-rate zone against a speed signal) keeps the step
 * but drops its intensity: duration is still compared, intensity is not guessed.
 */
public final class PlanReader {

    /** Zone 3, tempo, is the first heart-rate zone counted as work. */
    static final int HR_WORK_ZONE = 3;

    private PlanReader() {
    }

    public static List<PlannedStep> workSteps(JsonNode workoutDoc, EffortSignal signal, Optional<SportSettings> settings) {
        List<PlannedStep> out = new ArrayList<>();
        for (WorkoutDoc.DocStep s : WorkoutDoc.flatten(workoutDoc)) {
            if (s.seconds() == null || s.seconds() <= 0) {
                continue;
            }
            if ("hr_zone".equals(s.units())) {
                // Heart-rate zones cannot be compared with power or speed: tempo and above is work,
                // compared on its duration only.
                if (s.low() != null && s.low() >= HR_WORK_ZONE) {
                    out.add(new PlannedStep(s.seconds(), Double.NaN, Double.NaN));
                }
                continue;
            }
            double[] range = range(s, signal, settings);
            if (range == null) {
                continue;
            }
            boolean isWork = !Double.isNaN(signal.plateau()) && !Double.isNaN(range[0]) && range[0] >= signal.plateau();
            if (isWork) {
                out.add(new PlannedStep(s.seconds(), range[0], range[1]));
            }
        }
        return out;
    }

    /** Target as [low, high] in the signal's unit; NaN bounds when unknown; null to skip the step. */
    private static double[] range(WorkoutDoc.DocStep s, EffortSignal signal, Optional<SportSettings> settings) {
        if (s.units() == null || s.low() == null) {
            return null;
        }
        return switch (s.units()) {
            case "w" -> new double[] {s.low(), s.high()};
            case "power_zone" -> settings.map(SportSettings::thresholdPower).map(ftp -> {
                Zone z = ZoneSet.cyclingPower(ftp).zones().get((int) Math.round(s.low()) - 1);
                return new double[] {z.lower(), z.upper()};
            }).orElse(null);
            case "secs/km" -> new double[] {1000.0 / s.high(), 1000.0 / s.low()};
            case "secs/100m" -> new double[] {100.0 / s.high(), 100.0 / s.low()};
            default -> new double[] {Double.NaN, Double.NaN};
        };
    }
}
