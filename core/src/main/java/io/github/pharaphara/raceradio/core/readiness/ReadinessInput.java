package io.github.pharaphara.raceradio.core.readiness;

import java.util.List;

/**
 * Everything the morning call looks at.
 *
 * @param history        recovery data, oldest first; the last element is this morning
 * @param fitness        this morning's fitness, or null
 * @param fatigue        this morning's fatigue, or null
 * @param loadLast3Days  total load of the three previous days
 * @param feel           what the athlete reported this morning, or null when nothing was reported
 * @param recentFeels    the athlete's reports of the previous days, oldest first, possibly empty
 * @param health         illness or injury marked on the calendar, or {@link HealthContext#NONE}
 */
public record ReadinessInput(List<DayRecord> history, Double fitness, Double fatigue,
                             double loadLast3Days, Feel feel, List<Feel> recentFeels, HealthContext health) {

    /** Without health context. */
    public ReadinessInput(List<DayRecord> history, Double fitness, Double fatigue, double loadLast3Days, Feel feel, List<Feel> recentFeels) {
        this(history, fitness, fatigue, loadLast3Days, feel, recentFeels, HealthContext.NONE);
    }

    public ReadinessInput {
        health = health == null ? HealthContext.NONE : health;
        if (history == null || history.isEmpty()) {
            throw new IllegalArgumentException("readiness needs at least this morning's record");
        }
        history = List.copyOf(history);
        recentFeels = recentFeels == null ? List.of() : List.copyOf(recentFeels);
    }

    public DayRecord today() {
        return history.getLast();
    }
}
