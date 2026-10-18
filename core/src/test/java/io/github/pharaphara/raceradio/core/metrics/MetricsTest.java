package io.github.pharaphara.raceradio.core.metrics;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsTest {

    @Test
    void bestPowerIsTheHighestMeanOverTheWindow() {
        Samples ride = Samples.concat(Samples.constant(150, 600), Samples.constant(300, 300), Samples.constant(150, 600));
        assertThat(StreamMetrics.bestMean(ride, 300)).isEqualTo(300);
        assertThat(StreamMetrics.bestMean(ride, 600)).isEqualTo(225);
        assertThat(StreamMetrics.bestMean(ride, 3600)).isNaN();
    }

    @Test
    void bestTimeWorksFromAnyOrigin() {
        // 3 m/s for 400 s then 4 m/s for 400 s, starting at 42 km on the device's odometer.
        double[] d = new double[801];
        d[0] = 42_000;
        for (int i = 1; i < d.length; i++) {
            d[i] = d[i - 1] + (i <= 400 ? 3 : 4);
        }
        assertThat(StreamMetrics.bestTime(Samples.of(d), 1000)).isEqualTo(250);
        assertThat(StreamMetrics.bestTime(Samples.of(d), 5000)).isNaN();
    }

    @Test
    void heartRateAtEndurancePowerIgnoresTheWarmUpAndOtherEfforts() {
        int n = 3000;
        double[] watts = new double[n];
        double[] hr = new double[n];
        for (int i = 0; i < n; i++) {
            boolean endurance = i < 2000;
            watts[i] = endurance ? 175 : 260;
            hr[i] = i < 600 ? 110 : endurance ? 140 : 170;
        }
        Map<String, Double> m = StreamMetrics.compute("Ride",
                c -> Optional.ofNullable(Map.of("watts", Samples.of(watts), "heartrate", Samples.of(hr)).get(c)), 250, null);
        assertThat(m.get("hr_at_endurance_power")).isCloseTo(140, within(0.5));
        assertThat(m).containsKeys("best_power_5s", "best_power_20min").doesNotContainKey("best_power_60min");
    }

    @Test
    void aTrendComparesTheStartOfThePeriodWithItsEnd() {
        List<Trend.Point> pts = List.of(
                new Trend.Point(LocalDate.of(2026, 1, 5), 1.20), new Trend.Point(LocalDate.of(2026, 1, 20), 1.22),
                new Trend.Point(LocalDate.of(2026, 3, 3), 1.25),
                new Trend.Point(LocalDate.of(2026, 6, 2), 1.30), new Trend.Point(LocalDate.of(2026, 6, 9), 1.34));
        Trend.Result r = Trend.of(pts, Trend.Aggregate.MEDIAN, YearMonth.of(2026, 1), YearMonth.of(2026, 6));
        assertThat(r.months()).hasSize(6);
        assertThat(r.months().get(1).value()).isNaN();
        assertThat(r.sessions()).isEqualTo(5);
        assertThat(r.first()).isCloseTo(1.21, within(1e-9));
        assertThat(r.last()).isCloseTo(1.32, within(1e-9));
        assertThat(r.change()).isCloseTo(0.0909, within(1e-3));
        assertThat(r.best().value()).isEqualTo(1.34);

        Trend.Result times = Trend.of(pts, Trend.Aggregate.MIN, YearMonth.of(2026, 1), YearMonth.of(2026, 6));
        assertThat(times.best().value()).isEqualTo(1.20);
    }
}
