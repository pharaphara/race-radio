package io.github.pharaphara.raceradio.core.series;

import java.util.Arrays;

/**
 * Heart rate as a chest strap records it, with its glitches: a strap that loses contact reads zero or
 * jumps for a few seconds to values the heart never reached. A median over five seconds removes
 * those isolated spikes and keeps every real change, which lasts longer than that. Readings outside
 * 30 to 230 bpm are not heart rates and become missing.
 */
public final class HeartRate {

    static final int WINDOW = 5;
    static final double LOW = 30;
    static final double HIGH = 230;

    private HeartRate() {
    }

    public static Samples clean(Samples hr) {
        int n = hr.length();
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double v = hr.at(i);
            raw[i] = v >= LOW && v <= HIGH ? v : Double.NaN;
        }
        double[] out = new double[n];
        int half = WINDOW / 2;
        double[] window = new double[WINDOW];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(raw[i])) {
                out[i] = Double.NaN;
                continue;
            }
            int k = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
                if (!Double.isNaN(raw[j])) {
                    window[k++] = raw[j];
                }
            }
            double[] w = Arrays.copyOf(window, k);
            Arrays.sort(w);
            out[i] = k % 2 == 1 ? w[k / 2] : (w[k / 2 - 1] + w[k / 2]) / 2;
        }
        return Samples.of(out);
    }

    /** Highest mean heart rate held over {@code seconds}, from cleaned readings; NaN when shorter. */
    public static double maxHeld(Samples hr, int seconds) {
        Samples c = clean(hr);
        if (c.length() < seconds) {
            return Double.NaN;
        }
        double best = Double.NaN;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < c.length(); i++) {
            double v = c.at(i);
            if (!Double.isNaN(v)) {
                sum += v;
                count++;
            }
            if (i >= seconds) {
                double old = c.at(i - seconds);
                if (!Double.isNaN(old)) {
                    sum -= old;
                    count--;
                }
            }
            // Only windows almost fully covered: a strap that dropped out is not a held value.
            if (i >= seconds - 1 && count >= seconds * 0.9) {
                double mean = sum / count;
                best = Double.isNaN(best) ? mean : Math.max(best, mean);
            }
        }
        return best;
    }
}
