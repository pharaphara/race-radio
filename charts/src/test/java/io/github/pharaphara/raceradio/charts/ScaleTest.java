package io.github.pharaphara.raceradio.charts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScaleTest {

    @Test
    void roundTicksCoverTheRange() {
        Scale s = Scale.nice(23, 64, 0, 100, 4);
        assertThat(s.min()).isEqualTo(20);
        assertThat(s.max()).isEqualTo(80);
        assertThat(s.ticks(4)).containsExactly(20.0, 40.0, 60.0, 80.0);
    }

    @Test
    void paceTicksFallOnMultiplesOfThirtySecondsAndStayFew() {
        // 4:00 to 6:40 per kilometre, in seconds.
        Scale s = Scale.stepped(240, 400, 0, 100, 30);
        assertThat(s.ticksEvery(30, 6)).allMatch(t -> t % 30 == 0).hasSizeBetween(2, 6)
                .allMatch(t -> t >= s.min() && t <= s.max());
    }

    @Test
    void anInvertedScalePutsTheSmallerValueOnTop() {
        Scale s = Scale.stepped(240, 400, 10, 110, 30);
        assertThat(s.at(240)).isCloseTo(10, within(1e-9));
        assertThat(s.at(420)).isCloseTo(110, within(1e-9));
    }
}
