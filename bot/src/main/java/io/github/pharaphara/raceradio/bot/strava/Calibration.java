package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.archive.StreamCache;
import io.github.pharaphara.raceradio.core.segments.SegmentMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How far local timing is from Strava's, measured while both exist: each harvested Strava effort is
 * timed again from the same activity's GPS track (the one Intervals.icu holds, read from the local
 * cache), and the two times are compared. The pairing goes by start time, never by elapsed time, so
 * the comparison cannot flatter the matcher.
 */
public class Calibration {

    /** Efforts shorter than this are left out, as in the acceptance criterion. */
    static final int MIN_SECONDS = 60;
    static final double TARGET_GAP = 2.0;
    static final double TARGET_SHARE = 0.8;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Pair(String segment, int strava, double local) {

        double gap() {
            return local - strava;
        }
    }

    public record Report(int efforts, int found, List<Pair> pairs) {

        public double shareWithin(double seconds) {
            return efforts == 0 ? 0 : (double) pairs.stream().filter(p -> Math.abs(p.gap()) <= seconds).count() / efforts;
        }

        public String text() {
            List<Double> gaps = pairs.stream().map(p -> Math.abs(p.gap())).sorted().toList();
            double median = gaps.isEmpty() ? Double.NaN : gaps.get(gaps.size() / 2);
            double p90 = gaps.isEmpty() ? Double.NaN : gaps.get((int) Math.min(gaps.size() - 1, Math.round(0.9 * (gaps.size() - 1))));
            StringBuilder b = new StringBuilder(String.format(Locale.FRANCE,
                    "%d passages Strava de plus d'une minute, %d retrouvés localement (%.0f %%).%n"
                            + "Écart de %.0f s au plus pour %.0f %% des passages (objectif %.0f %%). Écart médian %.1f s, 90e centile %.1f s.",
                    efforts, found, efforts == 0 ? 0.0 : 100.0 * found / efforts, TARGET_GAP, 100 * shareWithin(TARGET_GAP),
                    100 * TARGET_SHARE, median, p90));
            pairs.stream().sorted(Comparator.comparingDouble((Pair p) -> -Math.abs(p.gap()))).limit(10).forEach(p ->
                    b.append(String.format(Locale.FRANCE, "%n  %s : Strava %d s, local %.1f s (%+.1f)", p.segment(), p.strava(), p.local(), p.gap())));
            return b.toString();
        }
    }

    private final JdbcTemplate jdbc;
    private final SegmentLibrary library;
    private final StreamCache streams;

    public Calibration(JdbcTemplate jdbc, SegmentLibrary library, StreamCache streams) {
        this.jdbc = jdbc;
        this.library = library;
        this.streams = streams;
    }

    public Report run() {
        Map<Long, SegmentLibrary.Segment> byId = new java.util.HashMap<>();
        library.segments().forEach(s -> byId.put(s.id(), s));
        List<Map<String, Object>> intervals = jdbc.queryForList("SELECT id, type, json_extract(json, '$.start_date_local') AS start FROM archive_activity");
        int efforts = 0;
        int found = 0;
        List<Pair> pairs = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList("SELECT id, summary FROM strava_activity WHERE detail IS NOT NULL")) {
            JsonNode summary = JSON.readTree((String) a.get("summary"));
            LocalDateTime start = time(summary.path("start_date_local").asString());
            Optional<String> icu = linked(intervals, start);
            if (icu.isEmpty() || !streams.has(icu.get())) {
                continue;
            }
            List<Map<String, Object>> list = jdbc.queryForList("SELECT segment_id, elapsed_seconds, json FROM strava_effort "
                    + "WHERE activity_id = ? AND elapsed_seconds >= ?", a.get("id"), MIN_SECONDS);
            if (list.isEmpty()) {
                continue;
            }
            Optional<SegmentMatcher.Track> track = SegmentLibrary.track(streams.get(icu.get()));
            if (track.isEmpty()) {
                continue;
            }
            for (Map<String, Object> e : list) {
                SegmentLibrary.Segment s = byId.get(((Number) e.get("segment_id")).longValue());
                if (s == null) {
                    continue;
                }
                efforts++;
                JsonNode effort = JSON.readTree((String) e.get("json"));
                double offset = Duration.between(start, time(effort.path("start_date_local").asString())).toSeconds();
                SegmentMatcher.Track t = track.get();
                Optional<SegmentMatcher.Passage> p = SegmentMatcher.match(t, s.trace(), s.metres()).stream()
                        .filter(x -> Math.abs(t.time()[x.start()] - offset) <= 60)
                        .min(Comparator.comparingDouble(x -> Math.abs(t.time()[x.start()] - offset)));
                if (p.isPresent()) {
                    found++;
                    pairs.add(new Pair(s.name(), ((Number) e.get("elapsed_seconds")).intValue(), p.get().seconds()));
                }
            }
        }
        return new Report(efforts, found, List.copyOf(pairs));
    }

    /** The Intervals.icu activity that started within two minutes of a Strava one. */
    private static Optional<String> linked(List<Map<String, Object>> intervals, LocalDateTime start) {
        return intervals.stream()
                .filter(r -> r.get("start") instanceof String s && Math.abs(Duration.between(start, time(s)).toSeconds()) <= 120)
                .map(r -> (String) r.get("id")).findFirst();
    }

    /** Strava writes local times with a trailing Z; Intervals.icu without. Both are wall-clock times. */
    static LocalDateTime time(String s) {
        return LocalDateTime.parse(s.endsWith("Z") ? s.substring(0, s.length() - 1) : s);
    }
}
