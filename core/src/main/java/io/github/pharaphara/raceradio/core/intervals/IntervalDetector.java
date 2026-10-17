package io.github.pharaphara.raceradio.core.intervals;

import io.github.pharaphara.raceradio.core.series.Samples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the work blocks in a session from one effort signal: power for cycling, and for running
 * power when the device records it, speed otherwise.
 *
 * <ol>
 *   <li>Smooth with a centred rolling mean, which unlike a trailing one does not shift boundaries.</li>
 *   <li>Split the session's effort into two populations with Otsu's method: the threshold that
 *       best separates "easy" from "hard" in this very session, with no fixed number.</li>
 *   <li>If the two populations are too close, the session was steady: report nothing rather than
 *       invent structure.</li>
 *   <li>Walk the smoothed signal with hysteresis around that threshold, so that a dip on a descent
 *       does not cut a block in two, then join blocks separated by less than half the window.</li>
 *   <li>Refine each boundary on a lightly smoothed signal, at the midpoint between that block's
 *       own level and the easy population.</li>
 *   <li>Drop blocks shorter than the minimum, and blocks below the endurance plateau: work means
 *       above endurance, not merely harder than the rest of the ride.</li>
 * </ol>
 *
 * <p>Replayed on real sessions, this finds clean repetitions indoors, recovers a twenty-minute
 * outdoor tempo block that lap-free detection had broken into four pieces, and reports scattered
 * efforts, not a fake series, on a ride where the planned intervals were not done.
 *
 * <p>When the session was ridden or run with the structured workout on the device, the device's
 * laps are the truth and this detector is not consulted; see {@link SessionStructure}.
 */
public final class IntervalDetector {

    public static final String MODEL = "intervals/otsu-hysteresis";

    /** Below this relative gap between the two populations, the session counts as steady. */
    static final double MIN_CONTRAST = 0.15;
    /** Hysteresis half-width, as a share of the gap between the two populations. */
    static final double HYSTERESIS = 0.10;
    /** Light smoothing used to place boundaries precisely. */
    static final int BOUNDARY_SMOOTHING = 5;
    /** Readings below this share of the median are stops and coasting, left out of the split. */
    static final double MOVING_SHARE = 0.25;

    private IntervalDetector() {
    }

    public static List<Block> detect(Samples effort, DetectionSettings settings) {
        double[] smooth = centredMean(effort, settings.smoothingSeconds());
        double[] moving = moving(smooth);
        if (moving.length < 2) {
            return List.of();
        }
        Split split = otsu(moving);
        if (split.high() <= 0 || (split.high() - split.low()) / split.high() < MIN_CONTRAST) {
            return List.of();
        }
        double gap = split.high() - split.low();
        double enter = split.threshold() + HYSTERESIS * gap;
        double leave = split.threshold() - HYSTERESIS * gap;

        List<int[]> raw = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < smooth.length; i++) {
            if (start < 0 && smooth[i] > enter) {
                start = i;
            } else if (start >= 0 && smooth[i] < leave) {
                raw.add(new int[] {start, i});
                start = -1;
            }
        }
        if (start >= 0) {
            raw.add(new int[] {start, smooth.length});
        }

        List<int[]> joined = new ArrayList<>();
        for (int[] b : raw) {
            if (!joined.isEmpty() && b[0] - joined.getLast()[1] < settings.smoothingSeconds() / 2) {
                joined.getLast()[1] = b[1];
            } else {
                joined.add(b);
            }
        }

        double[] fine = centredMean(effort, BOUNDARY_SMOOTHING);
        List<Block> blocks = new ArrayList<>();
        for (int[] b : joined) {
            int s = b[0];
            int e = b[1];
            double level = (mean(fine, s, e) + split.low()) / 2;
            while (s > 0 && fine[s - 1] > level) {
                s--;
            }
            while (s < e && fine[s] < level) {
                s++;
            }
            while (e > s && fine[e - 1] < level) {
                e--;
            }
            if (e - s < settings.minWorkSeconds()) {
                continue;
            }
            double m = effort.slice(s, e).mean();
            if (!Double.isNaN(settings.endurancePlateau()) && m < settings.endurancePlateau()) {
                continue;
            }
            blocks.add(new Block(s, e, m));
        }
        return List.copyOf(blocks);
    }

    record Split(double threshold, double low, double high) {
    }

    /** Otsu's method on a sorted copy: the cut that maximises the between-class variance. */
    static Split otsu(double[] values) {
        double[] v = values.clone();
        Arrays.sort(v);
        int n = v.length;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + v[i];
        }
        double best = -1;
        int cut = n / 2;
        for (int k = 1; k < n; k++) {
            if (v[k] == v[k - 1]) {
                continue;
            }
            double m0 = prefix[k] / k;
            double m1 = (prefix[n] - prefix[k]) / (n - k);
            double between = (double) k * (n - k) * (m0 - m1) * (m0 - m1);
            if (between > best) {
                best = between;
                cut = k;
            }
        }
        double low = prefix[cut] / cut;
        double high = (prefix[n] - prefix[cut]) / (n - cut);
        return new Split((v[cut - 1] + v[cut]) / 2, low, high);
    }

    private static double[] moving(double[] smooth) {
        double[] positive = Arrays.stream(smooth).filter(x -> x > 0).sorted().toArray();
        if (positive.length == 0) {
            return new double[0];
        }
        double floor = MOVING_SHARE * positive[positive.length / 2];
        return Arrays.stream(smooth).filter(x -> x > floor).toArray();
    }

    /** Centred rolling mean; missing readings are skipped, and a window with none gives zero. */
    static double[] centredMean(Samples s, int window) {
        int n = s.length();
        int half = window / 2;
        double[] sum = new double[n + 1];
        int[] count = new int[n + 1];
        for (int i = 0; i < n; i++) {
            double x = s.at(i);
            boolean present = !Double.isNaN(x);
            sum[i + 1] = sum[i] + (present ? x : 0);
            count[i + 1] = count[i] + (present ? 1 : 0);
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - half);
            int b = Math.min(n, i + half + 1);
            int c = count[b] - count[a];
            out[i] = c == 0 ? 0 : (sum[b] - sum[a]) / c;
        }
        return out;
    }

    private static double mean(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += v[i];
        }
        return s / (to - from);
    }
}
