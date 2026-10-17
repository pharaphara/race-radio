package io.github.pharaphara.raceradio.core.swim;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CriticalSwimSpeedTest {

    @Test
    void workedExampleFromTheProtocol() {
        // 400 m in 6:40 and 200 m in 3:00 give 200 m over 220 s: 0.909 m/s, 1:50 per 100 m.
        double css = CriticalSwimSpeed.fromTimeTrials(400, 180);
        assertThat(css).isCloseTo(0.909, within(0.001));
        assertThat(CriticalSwimSpeed.secondsPer100m(css)).isCloseTo(110, within(0.01));
    }

    @Test
    void impossibleTrialsAreRejected() {
        assertThatThrownBy(() -> CriticalSwimSpeed.fromTimeTrials(180, 400)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distancePerStrokeIsSpeedOverStrokeRate() {
        // 0.75 m/s at 30 strokes per minute: half a stroke per second, so 1.5 m per stroke.
        Samples speed = Samples.constant(0.75, 120);
        Samples rate = Samples.constant(30, 120);
        assertThat(StrokeEfficiency.distancePerStroke(speed, rate)).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void wallPushesAndPausesDoNotDistortDistancePerStroke() {
        Samples speed = Samples.concat(Samples.constant(0.75, 60), Samples.constant(0, 10), Samples.constant(0.75, 60));
        Samples rate = Samples.concat(Samples.constant(30, 60), Samples.constant(0, 10), Samples.constant(30, 60));
        assertThat(StrokeEfficiency.distancePerStroke(speed, rate)).isCloseTo(1.5, within(1e-9));
    }
}
