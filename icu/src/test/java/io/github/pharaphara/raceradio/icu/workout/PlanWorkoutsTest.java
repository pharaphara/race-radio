package io.github.pharaphara.raceradio.icu.workout;

import io.github.pharaphara.raceradio.core.plan.Phase;
import io.github.pharaphara.raceradio.core.plan.PlannedSession;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.plan.WeekBuilder;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Every workout the planner can produce must be publishable, or it would never reach the watch. */
class PlanWorkoutsTest {

    @Test
    void everyWorkoutOfEveryPhaseRendersWithoutProblems() {
        Map<DayOfWeek, Integer> days = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            days.put(d, 30 + 15 * d.getValue());
        }
        for (Phase phase : Phase.values()) {
            for (PlannedSession s : WeekBuilder.build(LocalDate.of(2026, 11, 16), phase, days, Set.of("Swim", "Ride", "Run"),
                    new Thresholds(250, 270, 130))) {
                assertThat(WorkoutText.problems(s.workout())).as(phase + " " + s.workout().name()).isEmpty();
                assertThat(WorkoutText.render(s.workout())).isNotBlank();
            }
        }
    }
}
