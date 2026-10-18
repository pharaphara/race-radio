package io.github.pharaphara.raceradio.core.metrics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A metric over months: one figure per month, and how the start of the period compares with its end.
 *
 * <p>Averages hide what matters here, so each month is summarised by its median (a typical session)
 * or, for records, by its best. The comparison takes the first and the last quarter of the months
 * that hold data, a quarter being at least one month, so a single odd session moves nothing.
 */
public final class Trend {

    /** How a month's sessions are summarised. */
    public enum Aggregate {
        /** A typical session. */
        MEDIAN,
        /** A record: the highest. */
        MAX,
        /** A record: the lowest, such as the best time over a distance. */
        MIN
    }

    public record Point(LocalDate day, double value) {
    }

    public record Month(YearMonth month, double value, int sessions) {
    }

    /**
     * @param months     every month of the period in order, NaN where no session counted
     * @param first      the figure for the first quarter of months with data, or NaN
     * @param last       the figure for the last quarter, or NaN
     * @param best       the best single session, or null; for MEDIAN, the highest value
     */
    public record Result(List<Month> months, int sessions, double first, double last, Point best) {

        /** Relative change from the first to the last quarter, or NaN when either is missing. */
        public double change() {
            return Double.isNaN(first) || Double.isNaN(last) || first == 0 ? Double.NaN : last / first - 1;
        }
    }

    private Trend() {
    }

    public static Result of(List<Point> points, Aggregate aggregate, YearMonth from, YearMonth to) {
        Map<YearMonth, List<Double>> byMonth = new TreeMap<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            byMonth.put(m, new ArrayList<>());
        }
        int n = 0;
        for (Point p : points) {
            List<Double> bucket = byMonth.get(YearMonth.from(p.day()));
            if (bucket != null && !Double.isNaN(p.value())) {
                bucket.add(p.value());
                n++;
            }
        }
        List<Month> months = new ArrayList<>();
        byMonth.forEach((m, values) -> months.add(new Month(m, summarise(values, aggregate), values.size())));

        List<Month> withData = months.stream().filter(m -> m.sessions() > 0).toList();
        double first = Double.NaN;
        double last = Double.NaN;
        if (withData.size() >= 2) {
            int q = Math.max(1, withData.size() / 4);
            first = summarise(pool(points, withData.subList(0, q)), aggregate);
            last = summarise(pool(points, withData.subList(withData.size() - q, withData.size())), aggregate);
        }
        Comparator<Point> order = Comparator.comparingDouble(Point::value);
        Point best = points.stream()
                .filter(p -> !Double.isNaN(p.value()) && byMonth.containsKey(YearMonth.from(p.day())))
                .max(aggregate == Aggregate.MIN ? order.reversed() : order).orElse(null);
        return new Result(List.copyOf(months), n, first, last, best);
    }

    private static List<Double> pool(List<Point> points, List<Month> months) {
        List<YearMonth> keys = months.stream().map(Month::month).toList();
        return points.stream().filter(p -> keys.contains(YearMonth.from(p.day())) && !Double.isNaN(p.value()))
                .map(Point::value).toList();
    }

    static double summarise(List<Double> values, Aggregate aggregate) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        return switch (aggregate) {
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            case MEDIAN -> {
                double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                yield v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
            }
        };
    }
}
