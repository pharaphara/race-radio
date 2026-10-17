package io.github.pharaphara.raceradio.core.plan;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where the athlete is in the season, from the nearest A race.
 *
 * <p>Taper over the last two weeks, cutting volume and keeping intensity, as the taper meta-analysis
 * found most effective. Specific work over the six weeks before it, with bike-run bricks. Base
 * before that. Every fourth week of base or specific work is a recovery week. Without a race:
 * maintenance, with the same recovery rhythm. These spans are coaching conventions; the taper rests
 * on evidence (Bosquet 2007).
 */
public enum Phase {
    BASE, SPECIFIC, TAPER, RACE_WEEK, RECOVERY_WEEK, MAINTENANCE;

    static final int TAPER_WEEKS = 2;
    static final int SPECIFIC_WEEKS = 6;
    static final int RECOVERY_EVERY = 4;

    public static Phase of(LocalDate weekStart, List<Objective> objectives, LocalDate seasonStart) {
        Optional<Objective> race = objectives.stream()
                .filter(o -> o.kind() == Objective.Kind.RACE && o.priority() == Objective.Priority.A && !o.date().isBefore(weekStart))
                .min(Comparator.comparing(Objective::date));
        long weekIndex = Math.max(0, ChronoUnit.WEEKS.between(seasonStart, weekStart));
        boolean recovery = (weekIndex + 1) % RECOVERY_EVERY == 0;
        if (race.isEmpty()) {
            return recovery ? RECOVERY_WEEK : MAINTENANCE;
        }
        long weeksToRace = ChronoUnit.DAYS.between(weekStart, race.get().date()) / 7;
        if (weeksToRace < 1) {
            return RACE_WEEK;
        }
        if (weeksToRace <= TAPER_WEEKS) {
            return TAPER;
        }
        if (recovery) {
            return RECOVERY_WEEK;
        }
        return weeksToRace <= TAPER_WEEKS + SPECIFIC_WEEKS ? SPECIFIC : BASE;
    }
}
