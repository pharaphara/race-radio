package io.github.pharaphara.raceradio.core.series;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HeartRateTest {

    @Test
    void anIsolatedSpikeAndDropoutsGoARealRiseStays() {
        double[] hr = new double[120];
        for (int i = 0; i < hr.length; i++) {
            hr[i] = i < 60 ? 150 : 150 + (i - 60);   // a real rise of one beat per second
        }
        hr[30] = 204;                                // a two-second strap glitch
        hr[31] = 203;
        hr[40] = 0;                                  // contact lost
        Samples c = HeartRate.clean(Samples.of(hr));
        assertThat(c.at(30)).isEqualTo(150);
        assertThat(c.at(31)).isEqualTo(150);
        assertThat(c.at(40)).isNaN();
        assertThat(c.at(110)).isEqualTo(200);
    }

    @Test
    void theMaximumHeldIsAMeanNotAPeak() {
        double[] hr = new double[600];
        java.util.Arrays.fill(hr, 170);
        for (int i = 300; i < 330; i++) {
            hr[i] = 190;
        }
        hr[100] = 210;
        hr[101] = 210;
        assertThat(HeartRate.maxHeld(Samples.of(hr), 30)).isCloseTo(190, within(0.01));
    }
}
