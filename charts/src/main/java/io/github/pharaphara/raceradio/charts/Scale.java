package io.github.pharaphara.raceradio.charts;

import java.util.ArrayList;
import java.util.List;

/** A linear scale with round ticks: steps of 1, 2 or 5 times a power of ten. */
public record Scale(double min, double max, double from, double to) {

    /** A scale over [lo, hi] widened to round ticks, mapped onto [from, to] in pixels. */
    public static Scale nice(double lo, double hi, double from, double to, int ticks) {
        if (!(hi > lo)) {
            hi = lo + 1;
        }
        double step = step(lo, hi, ticks);
        return new Scale(Math.floor(lo / step) * step, Math.ceil(hi / step) * step, from, to);
    }

    /** A scale over [lo, hi] widened to multiples of a fixed step, such as 30 seconds of pace. */
    public static Scale stepped(double lo, double hi, double from, double to, double step) {
        if (!(hi > lo)) {
            hi = lo + step;
        }
        return new Scale(Math.floor(lo / step) * step, Math.ceil(hi / step) * step, from, to);
    }

    /** Ticks on multiples of {@code step}, doubling the step until there are at most {@code maxTicks}. */
    public List<Double> ticksEvery(double step, int maxTicks) {
        while ((max - min) / step + 1 > maxTicks) {
            step *= 2;
        }
        List<Double> out = new ArrayList<>();
        for (double v = Math.ceil(min / step) * step; v <= max + step * 1e-9; v += step) {
            out.add(v);
        }
        return out;
    }

    public double at(double v) {
        return from + (v - min) / (max - min) * (to - from);
    }

    public List<Double> ticks(int count) {
        double step = step(min, max, count);
        List<Double> out = new ArrayList<>();
        for (double v = Math.ceil(min / step) * step; v <= max + step * 1e-9; v += step) {
            out.add(Math.abs(v) < step * 1e-9 ? 0 : v);
        }
        return out;
    }

    static double step(double lo, double hi, int ticks) {
        double raw = (hi - lo) / Math.max(1, ticks);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        return (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * mag;
    }
}
