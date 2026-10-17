package io.github.pharaphara.raceradio.core.zones;

import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.core.swim.CriticalSwimSpeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ZoneSetTest {

    @Test
    void cyclingZonesScaleWithThresholdPower() {
        ZoneSet z = ZoneSet.cyclingPower(250);
        assertThat(z.zoneOf(130).name()).isEqualTo("Recovery");
        assertThat(z.zoneOf(170).name()).isEqualTo("Aerobic");
        assertThat(z.zoneOf(250).name()).isEqualTo("Threshold");
        assertThat(z.zoneOf(290).name()).isEqualTo("VO2max");
        assertThat(z.zoneOf(900).name()).isEqualTo("Anaerobic");
    }

    @Test
    void timeInZonesCountsEverySecondWithAReadingExactlyOnce() {
        ZoneSet z = ZoneSet.cyclingPower(250);
        Samples ride = Samples.concat(Samples.constant(150, 600), Samples.of(Double.NaN), Samples.constant(265, 480));
        Map<Zone, Integer> t = z.timeInZones(ride);
        assertThat(t.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1080);
        assertThat(t.get(z.zoneOf(265))).isEqualTo(480);
    }

    @Test
    void forEverySportAFasterOrHarderEffortNeverLandsInAnEasierZone() {
        for (ZoneSet z : List.of(ZoneSet.cyclingPower(250), ZoneSet.heartRate(170),
                ZoneSet.runningPace(1000.0 / 270), ZoneSet.swimming(100.0 / 140))) {
            for (int i = 1; i < z.zones().size(); i++) {
                assertThat(z.zones().get(i).lower()).as(z.model()).isGreaterThan(z.zones().get(i - 1).lower());
            }
        }
    }

    @Test
    void runningThresholdPaceFallsInTheThresholdZone() {
        double thresholdSpeed = 1000.0 / 270; // 4:30 per kilometre
        ZoneSet z = ZoneSet.runningPace(thresholdSpeed);
        assertThat(z.zoneOf(thresholdSpeed).name()).isEqualTo("Threshold");
        assertThat(z.zoneOf(1000.0 / 330).name()).isEqualTo("Easy"); // 5:30, i.e. threshold + 60 s
    }

    @Test
    void swimmingZonesAreAnchoredOnCriticalSwimSpeed() {
        double css = CriticalSwimSpeed.fromTimeTrials(400, 180);
        ZoneSet z = ZoneSet.swimming(css);
        assertThat(z.zoneOf(css).name()).isEqualTo("Threshold");
        assertThat(z.zoneOf(100.0 / (CriticalSwimSpeed.secondsPer100m(css) + 10)).name()).isEqualTo("Aerobic");
    }

    @Test
    void zonesMustTileTheRangeWithoutGaps() {
        assertThatThrownBy(() -> new ZoneSet("x", "x", List.of(new Zone("1", "a", 0, 10), new Zone("2", "b", 11, 20))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zoneBoundariesAreStatedInTheModelName() {
        assertThat(ZoneSet.cyclingPower(250).model()).isEqualTo("friel-7/power");
        assertThat(ZoneSet.swimming(0.75).zones().getFirst().lower()).isCloseTo(0, within(1e-9));
    }
}
