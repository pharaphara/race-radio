package io.github.pharaphara.raceradio.charts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartsTest {

    private static double[] ramp(int n, double from, double to) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = from + (to - from) * i / (n - 1);
        }
        return v;
    }

    @Test
    void aSessionChartIsTwoPanelsSharingTheTimeAxisNeverTwoScalesOnOne() {
        double[] x = ramp(3600, 0, 3599);
        String svg = Charts.panels("Session", "sub", x, s -> (int) (s / 60) + " min", List.of(
                Charts.Panel.of("Power", List.of(new Charts.Series("power", ramp(3600, 150, 300), 0)), y -> (int) y + "").forSession(),
                Charts.Panel.of("Heart rate", List.of(new Charts.Series("hr", ramp(3600, 120, 170), 1)), y -> (int) y + "").forSession()),
                List.of(new Charts.Band(600, 780)), Theme.DARK);
        assertThat(svg).contains("Power").contains("Heart rate").contains("fill-opacity=\"0.12\"");
        assertThat(svg.split("<path ", -1)).hasSize(3);
    }

    @Test
    void aLongSessionIsDrawnWithAboutOnePointPerPixel() {
        double[][] d = Charts.downsample(ramp(12_000, 0, 1), ramp(12_000, 0, 1), 400);
        assertThat(d[0]).hasSize(400);
    }

    @Test
    void textIsEscapedAndNeverWearsASeriesColour() {
        String svg = Charts.bars("Zones <&>", null, List.of("Z1"), new double[] {60}, v -> "1 min", Theme.DARK);
        assertThat(svg).contains("Zones &lt;&amp;&gt;");
        for (String colour : Theme.DARK.series()) {
            assertThat(svg.lines().filter(l -> l.startsWith("<text") && l.contains(colour))).isEmpty();
        }
    }

    @Test
    void aFifthSeriesIsRefusedRatherThanGivenAGeneratedColour() {
        assertThatThrownBy(() -> Theme.DARK.slot(4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameSvgRasterisesToAPng() {
        String svg = Charts.bars("Zones", null, List.of("Z1", "Z2"), new double[] {60, 120}, v -> (int) v + " s", Theme.DARK);
        byte[] png = Png.of(svg, Charts.WIDTH, 2);
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }
}
