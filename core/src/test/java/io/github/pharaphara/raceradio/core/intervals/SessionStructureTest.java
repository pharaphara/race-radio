package io.github.pharaphara.raceradio.core.intervals;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStructureTest {

    private static final List<PlannedStep> THREE_BY_EIGHT = List.of(
            new PlannedStep(480, 3.57, 3.64), new PlannedStep(480, 3.57, 3.64), new PlannedStep(480, 3.57, 3.64));

    @Test
    void deviceLapsThatFollowTheWorkoutAreTheTruthAndTheirDeviationsAreReported() {
        List<Block> laps = List.of(new Block(900, 1380, 3.6), new Block(1565, 2047, 3.6), new Block(2228, 3124, 3.6));
        SessionStructure.Result r = SessionStructure.of(laps, THREE_BY_EIGHT, Samples.constant(3.0, 3200), Double.NaN);
        assertThat(r.source()).isEqualTo(SessionStructure.Source.DEVICE_LAPS);
        assertThat(r.repetitions()).extracting(List::size).containsExactly(2, 1);
        assertThat(r.againstPlan().getLast().deviations()).containsExactly(Deviation.LONGER);
    }

    @Test
    void lapsUnrelatedToThePlanAreIgnoredAndTheSessionIsDetected() {
        List<Block> autoLapsEveryKilometre = List.of(new Block(0, 300, 3.3), new Block(300, 600, 3.3), new Block(600, 900, 3.3));
        Samples run = Samples.concat(Samples.constant(3.0, 600), Samples.constant(3.6, 480), Samples.constant(3.0, 600));
        SessionStructure.Result r = SessionStructure.of(autoLapsEveryKilometre, THREE_BY_EIGHT, run, Double.NaN);
        assertThat(r.source()).isEqualTo(SessionStructure.Source.DETECTED);
        assertThat(r.work()).singleElement().satisfies(b -> assertThat(b.seconds()).isBetween(470, 490));
        assertThat(r.againstPlan()).filteredOn(c -> c.deviations().contains(Deviation.MISSING)).hasSize(2);
    }

    @Test
    void withoutAPlanTheSessionIsAlwaysDetectedAndNotComparedWithAnything() {
        Samples ride = Samples.concat(Samples.constant(150, 600), Samples.constant(280, 300), Samples.constant(150, 600));
        SessionStructure.Result r = SessionStructure.of(List.of(), List.of(), ride, Double.NaN);
        assertThat(r.source()).isEqualTo(SessionStructure.Source.DETECTED);
        assertThat(r.againstPlan()).isEmpty();
        assertThat(r.model()).isEqualTo(IntervalDetector.MODEL);
    }
}
