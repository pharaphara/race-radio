package io.github.pharaphara.raceradio.core.series;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SamplesTest {

    @Test
    void missingReadingsAreSkippedNotCountedAsZero() {
        Samples s = Samples.of(100, Double.NaN, 200);
        assertThat(s.mean()).isEqualTo(150.0);
        assertThat(s.presentCount()).isEqualTo(2);
    }

    @Test
    void rollingMeanKeepsLengthAndAlignment() {
        Samples r = Samples.of(0, 10, 20, 30).rollingMean(2);
        assertThat(r.length()).isEqualTo(4);
        assertThat(r.toArray()).containsExactly(0, 5, 15, 25);
    }

    @Test
    void rollingMeanIgnoresGapsInsideTheWindow() {
        Samples r = Samples.of(10, Double.NaN, 30).rollingMean(3);
        assertThat(r.at(2)).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void returnedArraysCannotMutateTheSeries() {
        Samples s = Samples.of(1, 2, 3);
        s.toArray()[0] = 99;
        assertThat(s.at(0)).isEqualTo(1.0);
    }
}
