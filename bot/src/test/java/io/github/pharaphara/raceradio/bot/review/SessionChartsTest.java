package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionChartsTest {

    @Test
    void aClimbIsShownAndPaddedToTheEffortsLength() {
        double[] relief = SessionCharts.relief(Samples.of(400, 410, 420, 430), 6);
        assertThat(relief).hasSize(6);
        assertThat(relief[3]).isEqualTo(430);
        assertThat(relief[5]).isNaN();
    }

    @Test
    void flatGroundOrNoAltitudeDrawsNothing() {
        assertThat(SessionCharts.relief(Samples.of(400, 405, 412, 401), 4)).isNull();
        assertThat(SessionCharts.relief(null, 4)).isNull();
    }
}
