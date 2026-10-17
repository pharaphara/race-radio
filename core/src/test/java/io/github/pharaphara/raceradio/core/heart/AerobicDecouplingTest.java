package io.github.pharaphara.raceradio.core.heart;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AerobicDecouplingTest {

    @Test
    void aPerfectlySteadyHeartRateShowsNoDecoupling() {
        assertThat(AerobicDecoupling.of(Samples.constant(180, 3600), Samples.constant(140, 3600)))
                .isCloseTo(0, within(1e-9));
    }

    @Test
    void heartRateCreepingUpAtTheSamePowerIsPositiveDecoupling() {
        Samples power = Samples.constant(180, 3600);
        Samples hr = Samples.concat(Samples.constant(140, 1800), Samples.constant(147, 1800));
        // Efficiency falls from 180/140 to 180/147, a loss of 7/147, about 4.8 percent.
        assertThat(AerobicDecoupling.of(power, hr)).isCloseTo(1 - 140.0 / 147, within(1e-9));
    }
}
