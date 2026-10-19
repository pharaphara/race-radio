package io.github.pharaphara.raceradio.icu.workout;

import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkoutTextTest {

    @Test
    void rendersOnlyFormsTheCalendarIsKnownToAccept() {
        assertThat(WorkoutText.render(SampleWorkouts.bikeVo2())).isEqualTo("""
                Warmup
                - 10m 120-160w 90rpm easy spin

                Main Set 5x
                - 3m 285-300w
                - 3m Z1

                Cooldown
                - 5m30s Z1""");
    }

    @Test
    void runAndSwimTargetsUseTheirOwnUnits() {
        Workout run = new Workout("Run", "Threshold", List.of(new Section("Main Set", 3, List.of(
                Step.of(new Length.Time(480), new Target.RunPace(275, 280)),
                Step.of(new Length.Time(120), new Target.HeartRateZone(1))))));
        assertThat(WorkoutText.render(run)).contains("- 8m 4:35-4:40/km Pace").contains("- 2m Z1 HR");

        Workout swim = new Workout("Swim", "Technique", List.of(new Section("Main Set", 4, List.of(
                Step.of(new Length.Distance(100), new Target.SwimPace(140, 145))))));
        assertThat(WorkoutText.render(swim)).contains("- 100mtr 2:20-2:25/100m Pace");
    }

    @Test
    void freeTextThatWouldBeReadAsAnInstructionIsRefused() {
        Workout w = new Workout("Ride", "x", List.of(new Section("Main Set", 1, List.of(
                new Step(new Length.Time(300), new Target.PowerZone(2), null, "then 3 x 5 min hard")))));
        assertThat(WorkoutText.problems(w)).singleElement().asString().contains("number or a keyword");
        assertThatThrownBy(() -> WorkoutText.render(w)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keywordsAreRefusedEvenWithoutDigits() {
        Workout w = new Workout("Run", "x", List.of(new Section("Main Set", 1, List.of(
                new Step(new Length.Time(300), new Target.HeartRateZone(2), null, "keep HR low")))));
        assertThat(WorkoutText.problems(w)).isNotEmpty();
    }

    @Test
    void aTargetTheSportCannotMeasureIsRefused() {
        Workout swimByWatts = new Workout("Swim", "x", List.of(new Section("Main Set", 1, List.of(
                Step.of(new Length.Distance(400), new Target.Power(200, 220))))));
        assertThat(WorkoutText.problems(swimByWatts)).anyMatch(p -> p.contains("not prescribed for Swim"));
    }

    @Test
    void frenchTitlesAndCuesAreFine() {
        Workout w = new Workout("Ride", "x", List.of(new Section("Retour au calme", 1, List.of(
                new Step(new Length.Time(300), new Target.PowerZone(1), null, "souple, mains en bas")))));
        assertThat(WorkoutText.problems(w)).isEmpty();
    }

    @Test
    void aNoteBecomesOneParagraphTheParserCannotTakeForAStep() {
        assertThat(WorkoutText.note(null)).isEmpty();
        assertThat(WorkoutText.note("- Pars doucement.\n- 10m Z2\nRéussi si la dernière est la meilleure, 3x"))
                .isEqualTo("Pars doucement. - 10m Z2 Réussi si la dernière est la meilleure, 3 fois")
                .doesNotContain("\n");
        assertThat(WorkoutText.note("mot ".repeat(400))).hasSizeLessThanOrEqualTo(WorkoutText.MAX_NOTE + 1).endsWith("…");
    }
}
