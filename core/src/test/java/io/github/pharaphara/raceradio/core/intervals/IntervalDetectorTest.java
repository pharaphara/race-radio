package io.github.pharaphara.raceradio.core.intervals;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IntervalDetectorTest {

    /** Deterministic ripple, so the signal is not unrealistically flat. */
    private static Samples ripple(double level, int seconds, double amplitude) {
        double[] v = new double[seconds];
        for (int i = 0; i < seconds; i++) {
            v[i] = level + amplitude * Math.sin(i * 0.7);
        }
        return Samples.of(v);
    }

    private static Samples session(Samples... parts) {
        return Samples.concat(parts);
    }

    @Test
    void findsFiveCleanRepetitionsOnAnIndoorSession() {
        List<Samples> parts = new ArrayList<>();
        parts.add(ripple(160, 600, 8));
        for (int i = 0; i < 5; i++) {
            parts.add(ripple(280, 180, 8));
            parts.add(ripple(150, 180, 8));
        }
        parts.add(ripple(140, 300, 8));
        List<Block> blocks = IntervalDetector.detect(session(parts.toArray(Samples[]::new)), DetectionSettings.forPlan(180, Double.NaN));
        assertThat(blocks).hasSize(5);
        assertThat(blocks).allSatisfy(b -> {
            assertThat(b.seconds()).isBetween(170, 190);
            assertThat(b.mean()).isCloseTo(280, within(10.0));
        });
    }

    @Test
    void reportsNothingOnASteadyRideRatherThanInventingStructure() {
        assertThat(IntervalDetector.detect(ripple(180, 3600, 15), DetectionSettings.forPlan(null, Double.NaN))).isEmpty();
    }

    @Test
    void bridgesShortDipsInsideALongOutdoorBlock() {
        // Twenty minutes at 240 W, with a twenty-second dip every three minutes: descents, junctions.
        List<Samples> block = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            block.add(ripple(240, 160, 15));
            block.add(ripple(120, 20, 10));
        }
        block.add(ripple(240, 120, 15));
        Samples ride = session(ripple(150, 900, 20), Samples.concat(block.toArray(Samples[]::new)), ripple(150, 900, 20));
        List<Block> blocks = IntervalDetector.detect(ride, DetectionSettings.forPlan(900, Double.NaN));
        assertThat(blocks).singleElement().satisfies(b -> assertThat(b.seconds()).isBetween(1150, 1250));
    }

    @Test
    void effortsBelowTheEndurancePlateauAreNotWork() {
        Samples ride = session(ripple(120, 900, 10), ripple(185, 600, 10), ripple(120, 900, 10));
        assertThat(IntervalDetector.detect(ride, DetectionSettings.forPlan(600, Double.NaN))).hasSize(1);
        assertThat(IntervalDetector.detect(ride, DetectionSettings.forPlan(600, 0.75 * 250))).isEmpty();
    }

    @Test
    void missingReadingsDoNotBreakDetection() {
        double[] v = session(ripple(150, 600, 5), ripple(280, 300, 5), ripple(150, 600, 5)).toArray();
        for (int i = 0; i < v.length; i += 7) {
            v[i] = Double.NaN;
        }
        assertThat(IntervalDetector.detect(Samples.of(v), DetectionSettings.forPlan(300, Double.NaN))).hasSize(1);
    }

    @Test
    void thePlanChoosesHowCloselyToLookWithinBounds() {
        assertThat(DetectionSettings.forPlan(30, Double.NaN).smoothingSeconds()).isEqualTo(5);
        assertThat(DetectionSettings.forPlan(180, Double.NaN).smoothingSeconds()).isEqualTo(30);
        assertThat(DetectionSettings.forPlan(1800, Double.NaN).smoothingSeconds()).isEqualTo(60);
        assertThat(DetectionSettings.forPlan(null, Double.NaN).smoothingSeconds()).isEqualTo(DetectionSettings.DEFAULT_SMOOTHING);
    }
}
