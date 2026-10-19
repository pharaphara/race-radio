package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.archive.StreamCache;
import io.github.pharaphara.raceradio.core.series.HeartRate;
import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Segment passages timed from the athlete's own GPS tracks, kept beside Strava's. Every archived
 * activity is scanned once (again when the set of segments grows); history mixes both sources, Strava's
 * time winning on a day it has one, so the record of a segment carries on after the subscription.
 */
public class LocalSegments {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSegments.class);

    /** One passage, whichever source it comes from. */
    public record Effort(LocalDate day, double seconds, Double watts, Double heartRate) {
    }

    private final JdbcTemplate jdbc;
    private final SegmentLibrary library;
    private final StreamCache streams;
    private final Clock clock;

    public LocalSegments(JdbcTemplate jdbc, SegmentLibrary library, StreamCache streams, Clock clock) {
        this.jdbc = jdbc;
        this.library = library;
        this.streams = streams;
        this.clock = clock;
    }

    /** Scans archived activities not yet scanned against the current segments; only streams on disk. */
    public int scanPending(int max) {
        int known = library.segments().size();
        if (known == 0) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.id, a.day FROM archive_activity a "
                + "LEFT JOIN segment_scan s ON s.activity_id = a.id WHERE s.activity_id IS NULL OR s.segments <> ? "
                + "ORDER BY a.day DESC LIMIT ?", known, max);
        int done = 0;
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            if (!streams.has(id)) {
                jdbc.update("INSERT OR REPLACE INTO segment_scan (activity_id, segments, scanned_at) VALUES (?, ?, ?)",
                        id, known, clock.instant().toString());
                continue;
            }
            try {
                scan(id, LocalDate.parse((String) r.get("day")), streams.get(id));
                done++;
            } catch (RuntimeException e) {
                LOG.warn("segment scan of {} failed: {}", id, e.getMessage());
            }
        }
        return done;
    }

    /** Times every passage of one activity and keeps them. Returns them, in order. */
    public List<SegmentLibrary.Found> scan(String activityId, LocalDate day, ActivityStreams s) {
        List<SegmentLibrary.Found> found = library.passages(s);
        Optional<Samples> watts = s.get("watts");
        Optional<Samples> hr = s.get("heartrate").map(HeartRate::clean);
        jdbc.update("DELETE FROM local_effort WHERE activity_id = ?", activityId);
        for (SegmentLibrary.Found f : found) {
            int from = f.passage().start();
            int to = f.passage().end() + 1;
            jdbc.update("INSERT OR REPLACE INTO local_effort (activity_id, segment_id, start_index, day, seconds, watts, heart_rate) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", activityId, f.segment().id(), from, day.toString(), f.passage().seconds(),
                    watts.map(w -> mean(w, from, to)).orElse(null), hr.map(h -> mean(h, from, to)).orElse(null));
        }
        jdbc.update("INSERT OR REPLACE INTO segment_scan (activity_id, segments, scanned_at) VALUES (?, ?, ?)",
                activityId, library.segments().size(), clock.instant().toString());
        return found;
    }

    /** Every passage of a segment, oldest first: Strava's, and local ones on days Strava has none. */
    public List<Effort> history(long segmentId) {
        List<Effort> out = new ArrayList<>();
        java.util.Set<String> stravaDays = new java.util.HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT day, elapsed_seconds, average_watts, average_hr FROM strava_effort "
                + "WHERE segment_id = ?", segmentId)) {
            stravaDays.add((String) r.get("day"));
            out.add(new Effort(LocalDate.parse((String) r.get("day")), ((Number) r.get("elapsed_seconds")).doubleValue(),
                    number(r.get("average_watts")), number(r.get("average_hr"))));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT day, seconds, watts, heart_rate FROM local_effort WHERE segment_id = ?", segmentId)) {
            if (!stravaDays.contains((String) r.get("day"))) {
                out.add(new Effort(LocalDate.parse((String) r.get("day")), ((Number) r.get("seconds")).doubleValue(),
                        number(r.get("watts")), number(r.get("heart_rate"))));
            }
        }
        out.sort(Comparator.comparing(Effort::day));
        return out;
    }

    /**
     * What is worth saying about an activity's passages: a record, a place among the three best, or
     * the gap to the record on a segment ridden often; at most {@code max} lines, best news first.
     */
    public List<String> notable(String activityId, int max) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT e.segment_id, e.day, e.seconds, s.name FROM local_effort e "
                + "JOIN strava_segment s ON s.id = e.segment_id WHERE e.activity_id = ? ORDER BY e.start_index", activityId)) {
            long segment = ((Number) r.get("segment_id")).longValue();
            double seconds = ((Number) r.get("seconds")).doubleValue();
            LocalDate day = LocalDate.parse((String) r.get("day"));
            if (seconds < 60) {
                continue;
            }
            List<Effort> before = history(segment).stream().filter(e -> e.day().isBefore(day)).toList();
            if (before.isEmpty()) {
                continue;
            }
            double best = before.stream().mapToDouble(Effort::seconds).min().orElse(Double.NaN);
            long faster = before.stream().filter(e -> e.seconds() < seconds).count();
            String name = (String) r.get("name");
            if (seconds < best) {
                lines.add(String.format(Locale.FRANCE, "%s : %s, record (%.0f s de mieux que %s)", name, clock(seconds), best - seconds, clock(best)));
            } else if (faster < 3) {
                lines.add(String.format(Locale.FRANCE, "%s : %s, %de temps sur %d passages, record %s", name, clock(seconds),
                        faster + 1, before.size() + 1, clock(best)));
            } else if (before.size() >= 3) {
                lines.add(String.format(Locale.FRANCE, "%s : %s, à %.0f s du record (%s), %d passages", name, clock(seconds),
                        seconds - best, clock(best), before.size() + 1));
            }
        }
        lines.sort(Comparator.comparingInt(l -> l.contains(", record (") ? 0 : l.contains("e temps sur") ? 1 : 2));
        return lines.stream().limit(max).toList();
    }

    private static Double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Double mean(Samples s, int from, int to) {
        double v = s.slice(Math.max(0, from), Math.min(to, s.length())).mean();
        return Double.isNaN(v) ? null : Math.round(v * 10) / 10.0;
    }

    static String clock(double seconds) {
        long t = Math.round(seconds);
        return t >= 3600 ? t / 3600 + "h" + String.format("%02d:%02d", t % 3600 / 60, t % 60) : t / 60 + ":" + String.format("%02d", t % 60);
    }
}
