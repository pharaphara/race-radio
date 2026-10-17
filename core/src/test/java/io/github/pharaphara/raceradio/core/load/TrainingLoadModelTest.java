package io.github.pharaphara.raceradio.core.load;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrainingLoadModelTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    void aSteadyDailyLoadIsEventuallyBothFitnessAndFatigueSoFormIsNeutral() {
        List<TrainingLoadModel.Day> days = TrainingLoadModel.run(every(50, 400), START, START.plusDays(399), 0, 0);
        TrainingLoadModel.Day last = days.getLast();
        assertThat(last.fitness()).isCloseTo(50, within(0.01));
        assertThat(last.fatigue()).isCloseTo(50, within(0.01));
        assertThat(last.form()).isCloseTo(0, within(0.01));
    }

    @Test
    void oneDayUsesExponentialDecayNotTheLinearShortcut() {
        // From zero, one day of load 100: exponential gives 100 * (1 - e^(-1/7)), about 13.31.
        // The linear shortcut would give 100 / 7, about 14.29. Intervals.icu matches the former.
        TrainingLoadModel.Day d = TrainingLoadModel.run(Map.of(START, 100.0), START, START, 0, 0).getFirst();
        assertThat(d.fatigue()).isCloseTo(100 * (1 - Math.exp(-1.0 / 7)), within(1e-9));
        assertThat(d.fitness()).isCloseTo(100 * (1 - Math.exp(-1.0 / 42)), within(1e-9));
    }

    @Test
    void aBigBlockLeavesFatigueAboveFitnessSoFormGoesNegative() {
        List<TrainingLoadModel.Day> days = TrainingLoadModel.run(every(120, 10), START, START.plusDays(9), 30, 30);
        assertThat(days.getLast().form()).isLessThan(-20);
    }

    @Test
    void rampIsFitnessTodayMinusFitnessAWeekEarlier() {
        List<TrainingLoadModel.Day> days = TrainingLoadModel.run(every(60, 30), START, START.plusDays(29), 0, 0);
        assertThat(days.get(6).ramp()).isNaN();
        TrainingLoadModel.Day today = days.get(20);
        assertThat(today.ramp()).isCloseTo(today.fitness() - days.get(13).fitness(), within(1e-9));
    }

    @Test
    void theRampPlannerProducesTheRampItWasAskedFor() {
        double current = 40;
        double daily = TrainingLoadModel.dailyLoadForRamp(current, 5);
        List<TrainingLoadModel.Day> week = TrainingLoadModel.run(every(daily, 7), START, START.plusDays(6), current, current);
        assertThat(week.getLast().fitness()).isCloseTo(current + 5, within(1e-9));
    }

    private static Map<LocalDate, Double> every(double load, int days) {
        Map<LocalDate, Double> m = new HashMap<>();
        for (int i = 0; i < days; i++) {
            m.put(START.plusDays(i), load);
        }
        return m;
    }
}
