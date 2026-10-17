package io.github.pharaphara.raceradio.charts;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * The two chart forms the coach needs. Never two vertical scales on one chart: two measures of
 * different scale go in two panels sharing the time axis. Marks are thin (2 px lines, bars at most
 * 24 px with a 4 px rounded end), grid and axes recessive, a legend whenever there are two series.
 */
public final class Charts {

    public static final double WIDTH = 540;
    static final double ROBUST_LOW = 0.05;
    static final double ROBUST_HIGH = 0.95;
    static final int MAX_TICKS = 6;
    static final double LEFT = 54;
    static final double RIGHT = 58;
    static final double TITLE = 52;
    static final double PANEL = 150;
    static final double PANEL_GAP = 34;
    static final double AXIS = 26;

    /** One line. {@code slot} is its categorical colour, fixed for the entity across all charts. */
    public record Series(String label, double[] y, int slot) {
    }

    /**
     * One panel with its own vertical scale.
     *
     * @param zeroLine   draw the baseline at zero and keep zero in range
     * @param inverted   smaller values at the top, as for a pace where a smaller number is faster
     * @param tickStep   fixed spacing between ticks, or NaN for round automatic ticks
     * @param endLabels  label each line's last value; meaningful on a trend, noise on a session
     * @param robust     scale on the 5th to 95th percentile, so stops, walking breaks or a sensor
     *                   glitch do not flatten everything else; values outside are clamped to the edge
     */
    public record Panel(String title, List<Series> series, DoubleFunction<String> yFormat, boolean zeroLine,
                        boolean inverted, double tickStep, boolean endLabels, boolean robust) {

        public static Panel of(String title, List<Series> series, DoubleFunction<String> yFormat) {
            return new Panel(title, series, yFormat, false, false, Double.NaN, true, false);
        }

        public Panel withZeroLine() {
            return new Panel(title, series, yFormat, true, inverted, tickStep, endLabels, robust);
        }

        /** For a session: robust range, no end label. */
        public Panel forSession() {
            return new Panel(title, series, yFormat, zeroLine, inverted, tickStep, false, true);
        }

        /**
         * Every reading in range. For a heart rate, whose lows are real (a stop, a descent), clamping
         * them to the edge would draw a flat line the athlete never had.
         */
        public Panel fullRange() {
            return new Panel(title, series, yFormat, zeroLine, inverted, tickStep, endLabels, false);
        }

        public Panel inverted(double step) {
            return new Panel(title, series, yFormat, zeroLine, true, step, endLabels, robust);
        }
    }

    /** A stretch of the x axis to wash, such as a work interval. */
    public record Band(double from, double to) {
    }

    private Charts() {
    }

    /** Panels stacked vertically over a shared x axis, with round automatic ticks on it. */
    public static String panels(String title, String subtitle, double[] x, DoubleFunction<String> xFormat,
                                List<Panel> panels, List<Band> bands, Theme t) {
        return panels(title, subtitle, x, xFormat, Double.NaN, panels, bands, t);
    }

    /**
     * Panels stacked vertically over a shared x axis.
     *
     * @param xTickStep  spacing of the x ticks in x units (600 for ten minutes of a session in
     *                   seconds, 7 for a week of days), doubled as needed; NaN for automatic
     */
    public static String panels(String title, String subtitle, double[] x, DoubleFunction<String> xFormat, double xTickStep,
                                List<Panel> panels, List<Band> bands, Theme t) {
        double height = TITLE + panels.size() * PANEL + (panels.size() - 1) * PANEL_GAP + AXIS + 8;
        Svg svg = header(new Svg(WIDTH, height, t), title, subtitle, t);
        double xMin = x[0];
        double xMax = x[x.length - 1];
        Scale sx = new Scale(xMin, xMax == xMin ? xMin + 1 : xMax, LEFT, WIDTH - RIGHT);

        double top = TITLE;
        for (int p = 0; p < panels.size(); p++) {
            Panel panel = panels.get(p);
            double bottom = top + PANEL;
            double[] range = range(panel);
            double lo = range[0];
            double hi = range[1];
            Scale sy = !Double.isNaN(panel.tickStep())
                    ? Scale.stepped(lo, hi, panel.inverted() ? top + 14 : bottom, panel.inverted() ? bottom : top + 14, panel.tickStep())
                    : Scale.nice(lo, hi, panel.inverted() ? top + 14 : bottom, panel.inverted() ? bottom : top + 14, 4);

            for (Band band : bands) {
                svg.rect(sx.at(band.from()), top + 14, sx.at(band.to()) - sx.at(band.from()), bottom - top - 14, t.slot(0), 0, 0.12);
            }
            for (double tick : Double.isNaN(panel.tickStep()) ? sy.ticks(4) : sy.ticksEvery(panel.tickStep(), MAX_TICKS)) {
                double yy = sy.at(tick);
                svg.line(LEFT, yy, WIDTH - RIGHT, yy, t.grid(), 1);
                svg.text(LEFT - 6, yy + 4, panel.yFormat().apply(tick), 11, t.textMuted(), "end", false);
            }
            if (panel.zeroLine()) {
                svg.line(LEFT, sy.at(0), WIDTH - RIGHT, sy.at(0), t.textMuted(), 1);
            }
            svg.text(LEFT, top + 4, panel.title(), 13, t.textSecondary(), "start", true);

            double legendX = WIDTH - RIGHT;
            if (panel.series().size() >= 2) {
                for (int i = panel.series().size() - 1; i >= 0; i--) {
                    Series s = panel.series().get(i);
                    double w = 7 * s.label().length() + 22;
                    legendX -= w;
                    svg.line(legendX, top, legendX + 14, top, t.slot(s.slot()), 2);
                    svg.text(legendX + 18, top + 4, s.label(), 11, t.textSecondary(), "start", false);
                }
            }
            for (Series s : panel.series()) {
                double[][] pts = downsample(x, s.y(), (int) (WIDTH - LEFT - RIGHT));
                double[] px = new double[pts[0].length];
                double[] py = new double[pts[0].length];
                for (int i = 0; i < px.length; i++) {
                    px[i] = sx.at(pts[0][i]);
                    py[i] = Double.isNaN(pts[1][i]) ? Double.NaN : sy.at(Math.max(sy.min(), Math.min(sy.max(), pts[1][i])));
                }
                svg.path(px, py, t.slot(s.slot()));
                int last = lastPresent(py);
                if (last >= 0 && panel.endLabels() && panel.series().size() <= 4) {
                    svg.circle(px[last], py[last], 4, t.slot(s.slot()), t.surface());
                    svg.text(px[last] + 8, py[last] + 4, panel.yFormat().apply(pts[1][last]), 11, t.textSecondary(), "start", false);
                }
            }
            top = bottom + PANEL_GAP;
        }
        double axisY = top - PANEL_GAP + 4;
        List<Double> xTicks = Double.isNaN(xTickStep) ? Scale.nice(xMin, xMax, LEFT, WIDTH - RIGHT, 5).ticks(5)
                : Scale.stepped(xMin, xMax, LEFT, WIDTH - RIGHT, xTickStep).ticksEvery(xTickStep, MAX_TICKS);
        for (double tick : xTicks) {
            if (tick < xMin || tick > xMax) {
                continue;
            }
            svg.text(sx.at(tick), axisY + 14, xFormat.apply(tick), 11, t.textMuted(), "middle", false);
        }
        return svg.toString();
    }

    /** Horizontal bars, one colour: each label names its bar, the value sits at the tip. */
    public static String bars(String title, String subtitle, List<String> labels, double[] values,
                              DoubleFunction<String> valueFormat, Theme t) {
        double row = 30;
        double labelWidth = 150;
        double height = TITLE + labels.size() * row + 12;
        Svg svg = header(new Svg(WIDTH, height, t), title, subtitle, t);
        double max = 0;
        for (double v : values) {
            max = Math.max(max, v);
        }
        double barMax = WIDTH - labelWidth - 70;
        for (int i = 0; i < labels.size(); i++) {
            double y = TITLE + i * row;
            svg.text(labelWidth - 10, y + row / 2 + 4, labels.get(i), 12, t.textSecondary(), "end", false);
            double w = max == 0 ? 0 : values[i] / max * barMax;
            double thickness = Math.min(18, row - 8);
            if (w > 0.5) {
                svg.bar(labelWidth, y + (row - thickness) / 2, w, thickness, t.slot(0), true);
            }
            svg.text(labelWidth + w + 6, y + row / 2 + 4, valueFormat.apply(values[i]), 11, t.textPrimary(), "start", false);
        }
        return svg.toString();
    }

    private static double[] range(Panel panel) {
        List<Double> all = new ArrayList<>();
        for (Series s : panel.series()) {
            for (double v : s.y()) {
                if (!Double.isNaN(v)) {
                    all.add(v);
                }
            }
        }
        if (all.isEmpty()) {
            return new double[] {0, 1};
        }
        all.sort(null);
        int n = all.size();
        double lo = panel.robust() ? all.get((int) (ROBUST_LOW * (n - 1))) : all.getFirst();
        double hi = panel.robust() ? all.get((int) Math.ceil(ROBUST_HIGH * (n - 1))) : all.getLast();
        if (panel.zeroLine()) {
            lo = Math.min(lo, 0);
            hi = Math.max(hi, 0);
        }
        return new double[] {lo, hi};
    }

    private static Svg header(Svg svg, String title, String subtitle, Theme t) {
        svg.text(LEFT - 40, 22, title, 16, t.textPrimary(), "start", true);
        if (subtitle != null && !subtitle.isBlank()) {
            svg.text(LEFT - 40, 40, subtitle, 12, t.textMuted(), "start", false);
        }
        return svg;
    }

    /** Mean per bucket, so a long session draws one point per pixel; gaps stay gaps. */
    static double[][] downsample(double[] x, double[] y, int buckets) {
        int n = Math.min(x.length, y.length);
        if (n <= buckets) {
            return new double[][] {java.util.Arrays.copyOf(x, n), java.util.Arrays.copyOf(y, n)};
        }
        List<double[]> out = new ArrayList<>();
        for (int b = 0; b < buckets; b++) {
            int from = (int) ((long) b * n / buckets);
            int to = (int) ((long) (b + 1) * n / buckets);
            double sx = 0;
            double sy = 0;
            int c = 0;
            for (int i = from; i < to; i++) {
                if (!Double.isNaN(y[i])) {
                    sy += y[i];
                    c++;
                }
                sx += x[i];
            }
            out.add(new double[] {sx / (to - from), c == 0 ? Double.NaN : sy / c});
        }
        double[][] r = new double[2][out.size()];
        for (int i = 0; i < out.size(); i++) {
            r[0][i] = out.get(i)[0];
            r[1][i] = out.get(i)[1];
        }
        return r;
    }

    private static int lastPresent(double[] v) {
        for (int i = v.length - 1; i >= 0; i--) {
            if (!Double.isNaN(v[i])) {
                return i;
            }
        }
        return -1;
    }
}
