package io.github.pharaphara.raceradio.core.segments;

import java.util.ArrayList;
import java.util.List;

/** Distances on the ground, in metres, and the polyline format segment traces come in. */
public final class Geo {

    static final double EARTH_RADIUS = 6_371_000;

    /** A point, in degrees. */
    public record Point(double lat, double lng) {
    }

    private Geo() {
    }

    /** Great-circle distance in metres (haversine). */
    public static double distance(Point a, Point b) {
        return distance(a.lat(), a.lng(), b.lat(), b.lng());
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /**
     * Metres east and north of {@code origin}, on a plane tangent to it: exact enough over the few
     * hundred metres around a start or finish line.
     */
    static double[] local(Point origin, double lat, double lng) {
        double x = Math.toRadians(lng - origin.lng()) * Math.cos(Math.toRadians(origin.lat())) * EARTH_RADIUS;
        double y = Math.toRadians(lat - origin.lat()) * EARTH_RADIUS;
        return new double[] {x, y};
    }

    /**
     * Decodes a Google encoded polyline, the format of Strava's segment maps, at the usual precision
     * of five decimals.
     */
    public static List<Point> decode(String polyline) {
        List<Point> out = new ArrayList<>();
        int index = 0;
        int lat = 0;
        int lng = 0;
        while (index < polyline.length()) {
            int[] r = next(polyline, index);
            lat += r[0];
            r = next(polyline, r[1]);
            lng += r[0];
            index = r[1];
            out.add(new Point(lat / 1e5, lng / 1e5));
        }
        return out;
    }

    /** One signed value and the index after it. */
    private static int[] next(String s, int index) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = s.charAt(index++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new int[] {(result & 1) != 0 ? ~(result >> 1) : result >> 1, index};
    }

    /** Points every {@code step} metres along a line, first and last kept. */
    public static List<Point> resample(List<Point> line, double step) {
        List<Point> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        out.add(line.getFirst());
        double carried = 0;
        for (int i = 1; i < line.size(); i++) {
            Point a = line.get(i - 1);
            Point b = line.get(i);
            double d = distance(a, b);
            double at = step - carried;
            while (d > 0 && at <= d) {
                double f = at / d;
                out.add(new Point(a.lat() + f * (b.lat() - a.lat()), a.lng() + f * (b.lng() - a.lng())));
                at += step;
            }
            carried = d - (at - step);
        }
        if (!out.getLast().equals(line.getLast())) {
            out.add(line.getLast());
        }
        return out;
    }
}
