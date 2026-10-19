package io.github.pharaphara.raceradio.core.segments;

import io.github.pharaphara.raceradio.core.segments.Geo.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds and times the passages of an activity over a segment, from GPS alone: what Strava does on
 * its servers, done here so that segments keep being timed without a subscription.
 *
 * <p>A passage starts where the track comes within {@value #START_RADIUS} m of the segment's start
 * (at the closest point of that approach), ends at the first such approach to its finish after it,
 * covers the segment's length within {@value #LENGTH_TOLERANCE} of it, and follows the segment:
 * {@value #COVER_SHARE} of points taken every {@value #STEP} m along the segment lie within
 * {@value #COVER_RADIUS} m of the track. Its time runs from the crossing of a line through the start,
 * across the segment's first direction, to the crossing of the same line at the finish, both
 * interpolated between readings. A track that runs the segment backwards does not match.
 */
public final class SegmentMatcher {

    static final double START_RADIUS = 25;
    static final double COVER_RADIUS = 30;
    static final double COVER_SHARE = 0.9;
    static final double LENGTH_TOLERANCE = 0.15;
    /** Slack added to the length tolerance, for short segments where 15 % is a few metres. */
    static final double LENGTH_SLACK = 40;
    static final double STEP = 20;

    /** An activity's GPS track, second by second; missing readings are NaN. */
    public record Track(double[] lat, double[] lng, double[] time) {

        public int length() {
            return Math.min(lat.length, Math.min(lng.length, time.length));
        }

        boolean present(int i) {
            return !Double.isNaN(lat[i]) && !Double.isNaN(lng[i]) && !Double.isNaN(time[i]);
        }
    }

    /** One passage over the segment: the readings it spans and its time in seconds. */
    public record Passage(int start, int end, double seconds) {
    }

    private SegmentMatcher() {
    }

    public static List<Passage> match(Track t, List<Point> segment, double segmentMetres) {
        List<Passage> out = new ArrayList<>();
        if (segment.size() < 2 || t.length() < 2) {
            return out;
        }
        List<Point> line = Geo.resample(segment, STEP);
        Point start = segment.getFirst();
        Point finish = segment.getLast();
        double[] along = along(t);
        double maxLength = segmentMetres * (1 + LENGTH_TOLERANCE) + LENGTH_SLACK;
        double minLength = segmentMetres * (1 - LENGTH_TOLERANCE) - LENGTH_SLACK;
        int i = 0;
        while (i < t.length()) {
            int s = closestApproach(t, start, i, t.length());
            if (s < 0) {
                break;
            }
            int e = -1;
            int j = s + 1;
            while (j < t.length() && along[j] - along[s] <= maxLength) {
                int candidate = closestApproach(t, finish, j, t.length());
                if (candidate < 0 || along[candidate] - along[s] > maxLength) {
                    break;
                }
                if (along[candidate] - along[s] >= minLength && covers(t, s, candidate, line)) {
                    e = candidate;
                    break;
                }
                j = endOfApproach(t, finish, candidate) + 1;
            }
            if (e < 0) {
                i = endOfApproach(t, start, s) + 1;
                continue;
            }
            double seconds = crossing(t, finish, direction(segment, false), e) - crossing(t, start, direction(segment, true), s);
            out.add(new Passage(s, e, seconds));
            i = e + 1;
        }
        return out;
    }

    /** Cumulative distance along the track; a missing reading adds nothing. */
    static double[] along(Track t) {
        double[] a = new double[t.length()];
        int last = -1;
        for (int i = 0; i < t.length(); i++) {
            if (i > 0) {
                a[i] = a[i - 1];
            }
            if (!t.present(i)) {
                continue;
            }
            if (last >= 0) {
                a[i] += Geo.distance(t.lat()[last], t.lng()[last], t.lat()[i], t.lng()[i]);
            }
            last = i;
        }
        return a;
    }

    /**
     * From {@code from}, the first time the track comes within {@value #START_RADIUS} m of the point,
     * the reading closest to it during that approach; -1 when it never does.
     */
    static int closestApproach(Track t, Point p, int from, int to) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            if (!t.present(i)) {
                continue;
            }
            double d = Geo.distance(t.lat()[i], t.lng()[i], p.lat(), p.lng());
            if (d < START_RADIUS) {
                if (d < bestDistance) {
                    best = i;
                    bestDistance = d;
                }
            } else if (best >= 0) {
                return best;
            }
        }
        return best;
    }

    /** The last reading of the approach that contains {@code i}. */
    static int endOfApproach(Track t, Point p, int i) {
        int j = i;
        while (j + 1 < t.length() && (!t.present(j + 1)
                || Geo.distance(t.lat()[j + 1], t.lng()[j + 1], p.lat(), p.lng()) < START_RADIUS)) {
            j++;
        }
        return j;
    }

    /** Whether enough of the segment lies near the track between two readings. */
    static boolean covers(Track t, int from, int to, List<Point> line) {
        int near = 0;
        for (Point p : line) {
            for (int i = from; i <= to; i++) {
                if (t.present(i) && Geo.distance(t.lat()[i], t.lng()[i], p.lat(), p.lng()) < COVER_RADIUS) {
                    near++;
                    break;
                }
            }
        }
        return near >= COVER_SHARE * line.size();
    }

    /** Unit vector, east and north, of the segment's first or last stretch of about 20 m. */
    static double[] direction(List<Point> segment, boolean first) {
        Point a = first ? segment.getFirst() : segment.getLast();
        Point b = a;
        if (first) {
            for (Point p : segment) {
                b = p;
                if (Geo.distance(a, p) >= STEP) {
                    break;
                }
            }
            double[] v = Geo.local(a, b.lat(), b.lng());
            return unit(v);
        }
        for (int i = segment.size() - 1; i >= 0; i--) {
            b = segment.get(i);
            if (Geo.distance(a, b) >= STEP) {
                break;
            }
        }
        double[] v = Geo.local(b, a.lat(), a.lng());
        return unit(v);
    }

    private static double[] unit(double[] v) {
        double n = Math.hypot(v[0], v[1]);
        return n == 0 ? new double[] {0, 0} : new double[] {v[0] / n, v[1] / n};
    }

    /**
     * The time the track crosses the line through {@code p} square to {@code dir}, interpolated
     * between the two readings around {@code at}; the reading's own time when no crossing is seen.
     */
    static double crossing(Track t, Point p, double[] dir, int at) {
        int from = Math.max(0, at - 10);
        int to = Math.min(t.length() - 1, at + 10);
        int prev = -1;
        double prevS = 0;
        double best = Double.NaN;
        int bestGap = Integer.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            if (!t.present(i)) {
                continue;
            }
            double[] v = Geo.local(p, t.lat()[i], t.lng()[i]);
            double s = v[0] * dir[0] + v[1] * dir[1];
            if (prev >= 0 && prevS < 0 && s >= 0) {
                double f = prevS == s ? 0 : -prevS / (s - prevS);
                double time = t.time()[prev] + f * (t.time()[i] - t.time()[prev]);
                int gap = Math.abs(i - at);
                if (gap < bestGap) {
                    best = time;
                    bestGap = gap;
                }
            }
            prev = i;
            prevS = s;
        }
        return Double.isNaN(best) ? t.time()[at] : best;
    }
}
