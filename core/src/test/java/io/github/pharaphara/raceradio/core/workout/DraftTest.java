package io.github.pharaphara.raceradio.core.workout;

import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.workout.Draft.DraftSection;
import io.github.pharaphara.raceradio.core.workout.Draft.DraftStep;
import io.github.pharaphara.raceradio.core.workout.Draft.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftTest {

    /** The fictional athlete: 250 W, 4:30/km, CSS 2:10/100 m. */
    private static final Thresholds T = new Thresholds(250, 270, 130);

    private static DraftStep time(int s, Kind k, double low, Double high) {
        return new DraftStep(s, null, k, low, high, null, "");
    }

    @Test
    void sharesOfThresholdPowerBecomeWatts() {
        Draft d = new Draft("Ride", "Seuil", List.of(
                new DraftSection("Warmup", 1, List.of(time(600, Kind.THRESHOLD_POWER_PERCENT, 50, 65.0))),
                new DraftSection("Main Set", 3, List.of(time(600, Kind.THRESHOLD_POWER_PERCENT, 95, 100.0),
                        time(300, Kind.POWER_ZONE, 1, null)))));
        Workout w = d.build(T);
        assertThat(w.sections().get(0).steps().get(0).target()).isEqualTo(new Target.Power(125, 163));
        assertThat(w.sections().get(1).steps().get(0).target()).isEqualTo(new Target.Power(238, 250));
        assertThat(Draft.seconds(w, T)).isEqualTo(600 + 3 * 900);
    }

    @Test
    void paceOffsetsAreTakenFromTheRightThreshold() {
        Workout run = new Draft("Run", "Tempo", List.of(new DraftSection("Main Set", 1,
                List.of(time(1200, Kind.THRESHOLD_PACE_OFFSET, 10, 20.0))))).build(T);
        assertThat(run.flatten().getFirst().target()).isEqualTo(new Target.RunPace(280, 290));

        Workout swim = new Draft("Swim", "CSS", List.of(new DraftSection("Main Set", 4,
                List.of(new DraftStep(null, 100, Kind.THRESHOLD_PACE_OFFSET, -3, 0.0, null, ""))))).build(T);
        assertThat(swim.flatten().getFirst().target()).isEqualTo(new Target.SwimPace(127, 130));
        assertThat(Draft.seconds(swim, T)).isEqualTo(4 * 129);
    }

    @Test
    void refusesWhatTheSportCannotMeasureOrWhatIsAbsurd() {
        assertThatThrownBy(() -> new Draft("Run", "x", List.of(new DraftSection("Main Set", 1,
                List.of(time(600, Kind.WATTS, 200, null))))).build(T)).hasMessageContaining("bike");
        assertThatThrownBy(() -> new Draft("Ride", "x", List.of(new DraftSection("Main Set", 1,
                List.of(time(600, Kind.THRESHOLD_POWER_PERCENT, 300, null))))).build(T)).hasMessageContaining("power out of range");
        assertThatThrownBy(() -> new Draft("Ride", "x", List.of(new DraftSection("Main Set", 1,
                List.of(time(600, Kind.THRESHOLD_POWER_PERCENT, 90, null))))).build(new Thresholds(null, 270, 130)))
                .hasMessageContaining("no threshold power");
        assertThatThrownBy(() -> new Draft("Ride", "x", List.of(new DraftSection("Main Set", 8,
                List.of(time(3600, Kind.POWER_ZONE, 2, null))))).build(T)).hasMessageContaining("six hours");
        assertThatThrownBy(() -> new Draft("Ride", "x", List.of(new DraftSection("Main Set", 1,
                List.of(new DraftStep(600, 1000, Kind.POWER_ZONE, 2, null, null, ""))))).build(T)).hasMessageContaining("not both");
    }
}
