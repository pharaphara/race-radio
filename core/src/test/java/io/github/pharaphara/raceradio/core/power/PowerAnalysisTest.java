package io.github.pharaphara.raceradio.core.power;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PowerAnalysisTest {

    private static final double THRESHOLD = 250;

    @Test
    void oneHourAtThresholdScoresOneHundred() {
        PowerSummary s = PowerAnalysis.summarise(Samples.constant(THRESHOLD, 3600), THRESHOLD);
        assertThat(s.weightedAveragePower()).isCloseTo(THRESHOLD, within(1e-9));
        assertThat(s.intensity()).isCloseTo(1.0, within(1e-9));
        assertThat(s.load()).isCloseTo(100.0, within(1e-9));
        assertThat(s.variability()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void halfAnHourAtThresholdScoresFifty() {
        assertThat(PowerAnalysis.summarise(Samples.constant(THRESHOLD, 1800), THRESHOLD).load())
                .isCloseTo(50.0, within(1e-9));
    }

    @Test
    void surgingCostsMoreThanRidingSteadyAtTheSameAverage() {
        // Alternating one minute at 360 W and one minute at 160 W averages 260 W, like a steady ride.
        Samples surging = Samples.concat(
                repeat(Samples.concat(Samples.constant(360, 60), Samples.constant(160, 60)), 30));
        Samples steady = Samples.constant(260, 3600);

        PowerSummary s = PowerAnalysis.summarise(surging, THRESHOLD);
        PowerSummary t = PowerAnalysis.summarise(steady, THRESHOLD);

        assertThat(s.averagePower()).isCloseTo(t.averagePower(), within(1e-9));
        assertThat(s.weightedAveragePower()).isGreaterThan(t.weightedAveragePower());
        assertThat(s.load()).isGreaterThan(t.load());
        assertThat(s.variability()).isGreaterThan(1.05);
    }

    @Test
    void aSessionWithoutPowerHasNoLoadRatherThanAFakeOne() {
        PowerSummary s = PowerAnalysis.summarise(Samples.of(Double.NaN, Double.NaN), THRESHOLD);
        assertThat(s.seconds()).isZero();
        assertThat(s.load()).isZero();
        assertThat(s.weightedAveragePower()).isNaN();
    }

    private static Samples[] repeat(Samples s, int times) {
        Samples[] out = new Samples[times];
        java.util.Arrays.fill(out, s);
        return out;
    }
}
