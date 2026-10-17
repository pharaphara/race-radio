package io.github.pharaphara.raceradio.core.intervals;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAlignmentTest {

    private static PlannedStep step(int seconds) {
        return new PlannedStep(seconds, 230, 240);
    }

    private static Block block(int start, int seconds, double mean) {
        return new Block(start, start + seconds, mean);
    }

    @Test
    void aLastRepHeldLongerOnPurposeIsLongerNotAMissedRep() {
        List<StepComparison> c = PlanAlignment.align(List.of(step(480), step(480), step(480)),
                List.of(block(900, 480, 235), block(1565, 482, 235), block(2228, 896, 235)));
        assertThat(c).extracting(StepComparison::deviations).containsExactly(
                java.util.Set.of(Deviation.AS_PLANNED), java.util.Set.of(Deviation.AS_PLANNED), java.util.Set.of(Deviation.LONGER));
    }

    @Test
    void findsWhichRepWasSkippedNotJustThatOneWas() {
        // Pyramid 1-2-3-2-1 minutes; the three-minute top was skipped.
        List<PlannedStep> plan = List.of(step(60), step(120), step(180), step(120), step(60));
        List<Block> done = List.of(block(0, 60, 235), block(200, 120, 235), block(500, 120, 235), block(800, 60, 235));
        List<StepComparison> c = PlanAlignment.align(plan, done);
        assertThat(c).hasSize(5);
        assertThat(c.get(2).deviations()).containsExactly(Deviation.MISSING);
        assertThat(c.get(2).planned().seconds()).isEqualTo(180);
    }

    @Test
    void aRepAbandonedHalfwayIsShorterNotMissing() {
        List<StepComparison> c = PlanAlignment.align(List.of(step(240)), List.of(block(0, 114, 235)));
        assertThat(c).singleElement().satisfies(x -> assertThat(x.deviations()).containsExactly(Deviation.SHORTER));
    }

    @Test
    void aBlockFarShorterThanTheRepIsNotThatRep() {
        List<StepComparison> c = PlanAlignment.align(List.of(step(480)), List.of(block(0, 150, 235)));
        assertThat(c).extracting(StepComparison::deviations)
                .containsExactlyInAnyOrder(java.util.Set.of(Deviation.MISSING), java.util.Set.of(Deviation.EXTRA));
    }

    @Test
    void measurementNoiseIsNotCalledEasier() {
        assertThat(PlanAlignment.compare(step(600), block(0, 600, 228))).containsExactly(Deviation.AS_PLANNED);
        assertThat(PlanAlignment.compare(step(600), block(0, 600, 215))).containsExactly(Deviation.EASIER);
    }

    @Test
    void surgesOutsideThePlanAreExtras() {
        List<StepComparison> c = PlanAlignment.align(List.of(step(240)), List.of(block(0, 12, 400), block(100, 240, 235)));
        assertThat(c.get(0).deviations()).containsExactly(Deviation.EXTRA);
        assertThat(c.get(1).deviations()).containsExactly(Deviation.AS_PLANNED);
    }
}
