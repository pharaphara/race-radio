package io.github.pharaphara.raceradio.core.plan;

import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeekCheckTest {

    private static final Thresholds T = new Thresholds(250, 270, 130);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);

    private static Workout threshold() {
        return new Workout("Ride", "Seuil", List.of(new Section("Main Set", 3,
                List.of(Step.of(new Length.Time(600), new Target.Power(240, 250)), Step.of(new Length.Time(300), new Target.PowerZone(1))))));
    }

    private static Workout easyRunWithStrides() {
        return new Workout("Run", "Facile", List.of(new Section("Main Set", 1, List.of(Step.of(new Length.Time(2400), new Target.RunPace(320, 340)))),
                new Section("Main Set", 6, List.of(Step.of(new Length.Time(20), new Target.RunPace(240, 250)), Step.of(new Length.Time(60), new Target.RunPace(360, 380))))));
    }

    private static WeekCheck.Planned on(int dayOffset, Workout w) {
        return new WeekCheck.Planned(MONDAY.plusDays(dayOffset), w);
    }

    @Test
    void stridesInAnEasyRunDoNotMakeItHard() {
        assertThat(WeekCheck.hard(threshold(), T)).isTrue();
        assertThat(WeekCheck.hard(easyRunWithStrides(), T)).isFalse();
    }

    @Test
    void aSoundWeekPasses() {
        assertThat(WeekCheck.problems(MONDAY, 7, List.of(on(0, easyRunWithStrides()), on(1, threshold()), on(3, easyRunWithStrides()),
                on(4, threshold()), on(5, easyRunWithStrides())), T)).isEmpty();
    }

    @Test
    void threeHardDaysInARowAndNoRestAreRefused() {
        List<String> p = WeekCheck.problems(MONDAY, 7, List.of(on(0, threshold()), on(1, threshold()), on(2, threshold()),
                on(3, easyRunWithStrides()), on(4, easyRunWithStrides()), on(5, easyRunWithStrides()), on(6, easyRunWithStrides())), T);
        assertThat(p).anyMatch(s -> s.startsWith("3 jours durs")).anyMatch(s -> s.startsWith("deux jours durs de suite"))
                .contains("aucun jour sans séance");
    }
}
