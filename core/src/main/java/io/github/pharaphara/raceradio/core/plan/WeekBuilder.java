package io.github.pharaphara.raceradio.core.plan;

import io.github.pharaphara.raceradio.core.workout.Workout;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds one week from the days the athlete has, the sports in season, the phase and the
 * thresholds. Rules, in the order they are applied:
 * <ol>
 *   <li>Volume follows the phase: full in base and specific work, about half in recovery and taper
 *       weeks (taper keeps intensity, recovery weeks drop it).</li>
 *   <li>The day with the most time gets the long session; in the specific phase, the bike-run
 *       brick instead.</li>
 *   <li>At most two hard sessions, never on consecutive days, one of them on the bike in base.
 *       Most of the week stays easy, as in the polarised distribution well-trained endurance
 *       athletes use (Seiler 2010).</li>
 *   <li>Swimming goes on short slots, as technique.</li>
 *   <li>Remaining days get easy sessions, alternating sports, and one day stays free from six
 *       available days up.</li>
 * </ol>
 * A sport without a threshold is left out: a workout is only prescribed in a unit its sport can
 * measure.
 */
public final class WeekBuilder {

    static final Set<String> BIKE = Set.of("Ride");
    static final int MIN_SESSION = 20;

    private WeekBuilder() {
    }

    public static List<PlannedSession> build(LocalDate monday, Phase phase, Map<DayOfWeek, Integer> available,
                                             Set<String> sports, Thresholds t) {
        boolean bike = sports.contains("Ride") && t.thresholdPower() != null;
        boolean run = sports.contains("Run") && t.thresholdPace() != null;
        boolean swim = sports.contains("Swim") && t.criticalSwimPace() != null;
        double volume = switch (phase) {
            case BASE, SPECIFIC -> 1.0;
            case MAINTENANCE -> 0.85;
            case RECOVERY_WEEK, TAPER -> 0.55;
            case RACE_WEEK -> 0.4;
        };
        int hardWanted = switch (phase) {
            case BASE, MAINTENANCE -> 1;
            case SPECIFIC -> 2;
            case TAPER -> 2;
            case RECOVERY_WEEK, RACE_WEEK -> 0;
        };

        List<DayOfWeek> days = new ArrayList<>(available.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() * volume >= MIN_SESSION)
                .map(Map.Entry::getKey).toList());
        if (days.size() >= 6) {
            DayOfWeek rest = days.stream().min(Comparator.comparing(available::get)).orElseThrow();
            days.remove(rest);
        }
        Map<DayOfWeek, Integer> minutes = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : days) {
            minutes.put(d, (int) Math.round(available.get(d) * volume / 5.0) * 5);
        }

        List<PlannedSession> out = new ArrayList<>();
        Set<DayOfWeek> used = new HashSet<>();
        Set<DayOfWeek> hardDays = new HashSet<>();

        // 2. Long session or brick on the roomiest day.
        days.stream().max(Comparator.comparing(minutes::get)).ifPresent(longDay -> {
            int m = minutes.get(longDay);
            LocalDate date = monday.with(longDay);
            if (phase == Phase.SPECIFIC && bike && run && m >= 50) {
                for (Workout w : Workouts.brick(t.thresholdPower(), t.thresholdPace(), m)) {
                    out.add(new PlannedSession(date, w, PlannedSession.Role.BRICK));
                }
                hardDays.add(longDay);
            } else if (bike) {
                out.add(new PlannedSession(date, Workouts.bikeEndurance(t.thresholdPower(), m), PlannedSession.Role.LONG));
            } else if (run) {
                out.add(new PlannedSession(date, Workouts.runEasy(t.thresholdPace(), Math.min(m, 90), false), PlannedSession.Role.LONG));
            }
            used.add(longDay);
        });

        // 3. Hard sessions, never on consecutive days, preferably away from the long day.
        Set<DayOfWeek> longDays = new HashSet<>(used);
        List<DayOfWeek> byTime = days.stream().filter(d -> !used.contains(d))
                .sorted(Comparator.comparing((DayOfWeek d) -> adjacent(d, longDays) ? 1 : 0)
                        .thenComparing(Comparator.comparing(minutes::get).reversed())).toList();
        int hard = hardDays.size();
        boolean bikeHardDone = false;
        for (DayOfWeek d : byTime) {
            if (hard >= hardWanted) {
                break;
            }
            if (adjacent(d, hardDays) || minutes.get(d) < 45) {
                continue;
            }
            LocalDate date = monday.with(d);
            int m = phase == Phase.TAPER ? Math.min(minutes.get(d), 60) : minutes.get(d);
            if (bike && !bikeHardDone) {
                Workout w = phase == Phase.BASE || phase == Phase.MAINTENANCE
                        ? Workouts.bikeSweetSpot(t.thresholdPower(), m) : Workouts.bikeThreshold(t.thresholdPower(), m);
                out.add(new PlannedSession(date, w, PlannedSession.Role.HARD));
                bikeHardDone = true;
            } else if (run) {
                out.add(new PlannedSession(date, Workouts.runThreshold(t.thresholdPace(), m), PlannedSession.Role.HARD));
            } else {
                continue;
            }
            hardDays.add(d);
            used.add(d);
            hard++;
        }

        // 4. Swimming on the shortest free slots.
        if (swim) {
            int swims = phase == Phase.RACE_WEEK ? 1 : 2;
            boolean light = phase == Phase.RECOVERY_WEEK || phase == Phase.TAPER || phase == Phase.RACE_WEEK;
            List<DayOfWeek> shortest = days.stream().filter(d -> !used.contains(d)).sorted(Comparator.comparing(minutes::get)).toList();
            for (DayOfWeek d : shortest) {
                if (swims == 0) {
                    break;
                }
                out.add(new PlannedSession(monday.with(d), Workouts.swimTechnique(t.criticalSwimPace(), light), PlannedSession.Role.TECHNIQUE));
                used.add(d);
                swims--;
            }
        }

        // 5. Easy sessions on what is left, alternating, run first when the bike already has the long day.
        boolean nextRun = true;
        for (DayOfWeek d : days.stream().filter(x -> !used.contains(x)).sorted().toList()) {
            LocalDate date = monday.with(d);
            int m = minutes.get(d);
            if (phase == Phase.RECOVERY_WEEK || phase == Phase.RACE_WEEK) {
                String sport = nextRun && run ? "Run" : bike ? "Ride" : "Run";
                if (("Run".equals(sport) && run) || ("Ride".equals(sport) && bike)) {
                    out.add(new PlannedSession(date, Workouts.recovery(sport, t, Math.min(m, 45)), PlannedSession.Role.RECOVERY));
                }
            } else if (nextRun && run) {
                out.add(new PlannedSession(date, Workouts.runEasy(t.thresholdPace(), Math.min(m, 60), phase == Phase.BASE),
                        PlannedSession.Role.EASY));
            } else if (bike) {
                out.add(new PlannedSession(date, Workouts.bikeEndurance(t.thresholdPower(), m), PlannedSession.Role.EASY));
            } else if (run) {
                out.add(new PlannedSession(date, Workouts.runEasy(t.thresholdPace(), Math.min(m, 60), false), PlannedSession.Role.EASY));
            }
            nextRun = !nextRun;
        }
        out.sort(Comparator.comparing(PlannedSession::day));
        return out;
    }

    private static boolean adjacent(DayOfWeek d, Set<DayOfWeek> hardDays) {
        return hardDays.contains(d) || hardDays.contains(d.plus(1)) || hardDays.contains(d.minus(1));
    }
}
