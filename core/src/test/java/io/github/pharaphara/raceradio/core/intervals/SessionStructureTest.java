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

    @Test
    void lapsAreReadThroughThePlanAndWarmUpJogsLeaveTheSummary() {
        // Speeds in m/s: two easy jogging laps, then 5 reps of about 4 min at 4.0 m/s with recoveries,
        // the first slower because it climbed. The plan holds the 5 reps only.
        PlannedStep rep = new PlannedStep(247, 4.0, 4.1);
        List<Block> laps = List.of(new Block(0, 348, 2.95), new Block(348, 686, 2.96),
                new Block(700, 966, 3.77), new Block(1100, 1352, 3.95), new Block(1500, 1746, 4.05),
                new Block(1900, 2151, 3.98), new Block(2300, 2547, 3.99));
        SessionStructure.Result r = SessionStructure.of(laps, List.of(rep, rep, rep, rep, rep), Samples.constant(3.5, 2600), 2.7);
        assertThat(r.source()).isEqualTo(SessionStructure.Source.DEVICE_LAPS);
        assertThat(r.work()).hasSize(5);
        assertThat(r.repetitions()).hasSize(1).first().satisfies(g -> assertThat(g).hasSize(5));
    }

    @Test
    void aRepHeldMuchLongerOnPurposeStandsAlone() {
        PlannedStep rep = new PlannedStep(480, 3.55, 3.65);
        List<Block> laps = List.of(new Block(600, 1080, 3.57), new Block(1200, 1682, 3.62), new Block(1800, 2696, 3.70));
        SessionStructure.Result r = SessionStructure.of(laps, List.of(rep, rep, rep), Samples.constant(3.2, 2800), 2.7);
        assertThat(r.repetitions()).extracting(List::size).containsExactly(2, 1);
    }
}
