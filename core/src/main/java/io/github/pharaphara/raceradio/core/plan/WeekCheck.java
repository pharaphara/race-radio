package io.github.pharaphara.raceradio.core.plan;

import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rules a proposed week must keep, checked in code rather than trusted to whoever wrote it:
 * at most two hard sessions, never on consecutive days, at least one day without anything, at most
 * two sessions on one day (a brick). Most of the week stays easy, as in the polarised distribution
 * of well-trained endurance athletes (Seiler 2010).
 */
public final class WeekCheck {

    /** A session is hard when it holds at least this much time at threshold or above. */
    static final int HARD_SECONDS = 300;
    static final int MAX_HARD = 2;
    static final int MAX_PER_DAY = 2;

    public record Planned(LocalDate day, Workout workout) {
    }

    private WeekCheck() {
    }

    /** The broken rules, in French for the athlete; empty when the week is sound. */
    public static List<String> problems(LocalDate start, int days, List<Planned> week, Thresholds t) {
        List<String> out = new ArrayList<>();
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        TreeSet<LocalDate> hard = new TreeSet<>();
        for (Planned p : week) {
            if (p.day().isBefore(start) || !p.day().isBefore(start.plusDays(days))) {
                out.add("séance hors de la semaine le " + p.day());
            }
            perDay.merge(p.day(), 1, Integer::sum);
            if (hard(p.workout(), t)) {
                hard.add(p.day());
            }
        }
        if (hard.size() > MAX_HARD) {
            out.add(hard.size() + " jours durs, " + MAX_HARD + " au plus");
        }
        for (LocalDate d : hard) {
            if (hard.contains(d.plusDays(1))) {
                out.add("deux jours durs de suite, le " + d + " et le lendemain");
            }
        }
        if (perDay.size() >= days) {
            out.add("aucun jour sans séance");
        }
        perDay.forEach((d, n) -> {
            if (n > MAX_PER_DAY) {
                out.add(n + " séances le " + d + ", " + MAX_PER_DAY + " au plus");
            }
        });
        return out;
    }

    /** At least {@value #HARD_SECONDS} seconds at or above threshold. */
    public static boolean hard(Workout w, Thresholds t) {
        int above = 0;
        for (Section s : w.sections()) {
            for (Step step : s.steps()) {
                if (atThreshold(step.target(), t)) {
                    above += s.repeats() * Draft.seconds(step, t);
                }
            }
        }
        return above >= HARD_SECONDS;
    }

    private static boolean atThreshold(Target target, Thresholds t) {
        return switch (target) {
            case Target.Power p -> t.thresholdPower() != null && p.high() >= 0.95 * t.thresholdPower();
            case Target.PowerZone z -> z.zone() >= 4;
            case Target.HeartRateZone z -> z.zone() >= 4;
            case Target.RunPace p -> t.thresholdPace() != null && p.fast() <= t.thresholdPace() + 5;
            case Target.SwimPace p -> t.criticalSwimPace() != null && p.fast() <= t.criticalSwimPace();
        };
    }
}
