package io.github.pharaphara.raceradio.core.merge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityMergeTest {

    @Test
    void theSecondPartContinuesInTimeAndDistanceAndThePauseStaysAPause() {
        Map<String, double[]> a = Map.of("time", new double[] {0, 1, 2}, "distance", new double[] {0, 5, 10}, "watts", new double[] {100, 110, 120});
        Map<String, double[]> b = Map.of("time", new double[] {0, 1}, "distance", new double[] {0, 6}, "watts", new double[] {130, 140});
        Map<String, double[]> m = ActivityMerge.concat(a, b, 600);
        assertThat(m.get("time")).containsExactly(0, 1, 2, 600, 601);
        assertThat(m.get("distance")).containsExactly(0, 5, 10, 10, 16);
        assertThat(m.get("watts")).containsExactly(100, 110, 120, 130, 140);
    }

    @Test
    void aStreamOnOneSideOnlyIsKeptAndLeftEmptyOnTheOther() {
        Map<String, double[]> a = Map.of("time", new double[] {0, 1}, "heartrate", new double[] {120, 121});
        Map<String, double[]> b = Map.of("time", new double[] {0}, "watts", new double[] {200});
        Map<String, double[]> m = ActivityMerge.concat(a, b, 100);
        assertThat(m.get("heartrate")[2]).isNaN();
        assertThat(m.get("watts")[0]).isNaN();
        assertThat(m.get("watts")[2]).isEqualTo(200);
    }

    @Test
    void misalignedStreamsAreRefused() {
        assertThatThrownBy(() -> ActivityMerge.concat(Map.of("time", new double[] {0, 1}, "watts", new double[] {1}), Map.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
