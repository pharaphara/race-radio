package io.github.pharaphara.raceradio.core.merge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Joins the streams of an activity that was cut in two, a watch stopped and restarted by mistake.
 *
 * <p>The second part's time is shifted by the real interval between the two starts, so the pause
 * stays a pause rather than being squeezed out; its distance continues from where the first ended.
 * A stream present on one side only is kept, empty on the other side, never filled in.
 */
public final class ActivityMerge {

    public static final String TIME = "time";
    public static final String DISTANCE = "distance";

    private ActivityMerge() {
    }

    public static Map<String, double[]> concat(Map<String, double[]> first, Map<String, double[]> second, long secondsBetweenStarts) {
        int n1 = length(first);
        int n2 = length(second);
        Set<String> names = new LinkedHashSet<>(first.keySet());
        names.addAll(second.keySet());
        Map<String, double[]> out = new LinkedHashMap<>();
        double distanceSoFar = last(first.get(DISTANCE));
        for (String name : names) {
            double[] a = first.getOrDefault(name, nan(n1));
            double[] b = second.getOrDefault(name, nan(n2)).clone();
            if (TIME.equals(name)) {
                for (int i = 0; i < b.length; i++) {
                    b[i] += secondsBetweenStarts;
                }
            } else if (DISTANCE.equals(name) && !Double.isNaN(distanceSoFar)) {
                for (int i = 0; i < b.length; i++) {
                    b[i] += distanceSoFar;
                }
            }
            double[] joined = Arrays.copyOf(a, n1 + n2);
            System.arraycopy(b, 0, joined, n1, n2);
            out.put(name, joined);
        }
        return out;
    }

    private static int length(Map<String, double[]> streams) {
        int n = -1;
        for (Map.Entry<String, double[]> e : streams.entrySet()) {
            if (n >= 0 && e.getValue().length != n) {
                throw new IllegalArgumentException("stream " + e.getKey() + " does not cover the same seconds as the others");
            }
            n = e.getValue().length;
        }
        return Math.max(n, 0);
    }

    private static double last(double[] v) {
        if (v == null) {
            return Double.NaN;
        }
        for (int i = v.length - 1; i >= 0; i--) {
            if (!Double.isNaN(v[i])) {
                return v[i];
            }
        }
        return Double.NaN;
    }

    private static double[] nan(int n) {
        double[] v = new double[n];
        Arrays.fill(v, Double.NaN);
        return v;
    }
}
