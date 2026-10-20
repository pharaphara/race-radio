package io.github.pharaphara.raceradio.bot.archive;

import io.github.pharaphara.raceradio.core.metrics.StreamMetrics;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The local copy of the athlete's history. Intervals.icu is asked once for each activity and each
 * wellness day, and the answer is kept whole; the few figures it does not provide are computed once
 * from the streams. Questions about any period are then answered from here, with no call at all.
 *
 * <p>Cost to Intervals.icu: two list calls per sync, and one streams download per new session of a
 * sport that has stream figures, spaced out during the first back-fill.
 */
public class Archive {

    private static final Logger LOG = LoggerFactory.getLogger(Archive.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Calendar entries as the API sent them: far more fields than the record keeps. */
    private static final JsonMapper EVENTS = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    /** Sports whose streams hold figures worth computing; the others never trigger a download. */
    static final Set<String> WITH_STREAM_FIGURES = Set.of("Ride", "VirtualRide", "GravelRide", "MountainBikeRide",
            "Run", "TrailRun", "VirtualRun", "Treadmill", "Swim", "OpenWaterSwim");
    /** How far back the first back-fill goes. */
    static final int HISTORY_DAYS = 400;

    private final JdbcTemplate jdbc;
    private final IntervalsClient intervals;
    private final StreamCache streams;
    private final Clock clock;

    public Archive(JdbcTemplate jdbc, IntervalsClient intervals, StreamCache streams, Clock clock) {
        this.jdbc = jdbc;
        this.intervals = intervals;
        this.streams = streams;
        this.clock = clock;
    }

    /** Copies activities and wellness days of the period. Two calls. Returns how many activities were new. */
    public int sync(LocalDate from, LocalDate to) {
        String now = clock.instant().toString();
        int added = 0;
        for (JsonNode a : intervals.activitiesRaw(from, to)) {
            String id = a.path("id").asString();
            String start = a.path("start_date_local").asString();
            if (id.isBlank() || start.length() < 10) {
                continue;
            }
            int n = jdbc.update("UPDATE archive_activity SET json = ?, type = ?, day = ?, fetched_at = ? WHERE id = ?",
                    a.toString(), a.path("type").asString(), start.substring(0, 10), now, id);
            if (n == 0) {
                jdbc.update("INSERT INTO archive_activity (id, day, type, json, fetched_at) VALUES (?, ?, ?, ?, ?)",
                        id, start.substring(0, 10), a.path("type").asString(), a.toString(), now);
                added++;
            }
        }
        for (JsonNode w : intervals.wellnessRaw(from, to)) {
            String day = w.path("id").asString();
            if (day.length() == 10) {
                jdbc.update("INSERT INTO archive_wellness (day, json, fetched_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(day) DO UPDATE SET json = excluded.json, fetched_at = excluded.fetched_at", day, w.toString(), now);
            }
        }
        return added;
    }

    /**
     * Computes the missing figures, most recent sessions first. Streams already on disk cost
     * nothing; a download is followed by {@code pause}, so a back-fill never hammers the service.
     *
     * @return how many sessions were brought up to date
     */
    public int computePending(int max, Duration pause) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type, json FROM archive_activity WHERE computed_version < ? ORDER BY day DESC LIMIT ?",
                StreamMetrics.VERSION, max);
        int done = 0;
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            String type = (String) r.get("type");
            try {
                if (WITH_STREAM_FIGURES.contains(type)) {
                    boolean download = !streams.has(id);
                    JsonNode a = JSON.readTree((String) r.get("json"));
                    var s = streams.get(id);
                    Map<String, Double> figures = StreamMetrics.compute(type, s::get,
                            a.path("icu_ftp").isNumber() ? a.path("icu_ftp").asInt() : null,
                            a.path("threshold_pace").isNumber() ? a.path("threshold_pace").asDouble() : null);
                    jdbc.update("DELETE FROM computed_metric WHERE activity_id = ?", id);
                    figures.forEach((k, v) -> jdbc.update("INSERT INTO computed_metric (activity_id, metric, value) VALUES (?, ?, ?)", id, k, v));
                    if (download && !pause.isZero()) {
                        Thread.sleep(pause);
                    }
                }
                jdbc.update("UPDATE archive_activity SET computed_version = ? WHERE id = ?", StreamMetrics.VERSION, id);
                done++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return done;
            } catch (RuntimeException e) {
                // Left pending: a network error is retried at the next sync.
                LOG.warn("archiving the figures of {} failed: {}", id, e.toString());
            }
        }
        return done;
    }

    /** The whole history, once: run in the background at start, a no-op when already done. */
    public void backfill() {
        LocalDate today = LocalDate.now(clock);
        try {
            int added = sync(today.minusDays(HISTORY_DAYS), today);
            int done = computePending(Integer.MAX_VALUE, Duration.ofSeconds(2));
            if (added > 0 || done > 0) {
                LOG.info("archive: {} new activities, figures computed for {}", added, done);
            }
        } catch (RuntimeException e) {
            LOG.warn("archive back-fill failed, retried tonight: {}", e.toString());
        }
    }

    /** Keeps the recent past fresh: names, merges and late uploads change within days. */
    public void refresh(int days) {
        LocalDate today = LocalDate.now(clock);
        try {
            sync(today.minusDays(days), today);
            syncEvents();
            computePending(200, Duration.ofSeconds(2));
        } catch (RuntimeException e) {
            LOG.warn("archive refresh failed: {}", e.toString());
        }
    }

    /** Days ahead kept in the calendar cache. */
    static final int EVENTS_AHEAD = 14;

    /** Replaces the cached calendar from today on. One call. */
    public void syncEvents() {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = today.plusDays(EVENTS_AHEAD);
        List<JsonNode> events = intervals.eventsRaw(today, to);
        String now = clock.instant().toString();
        jdbc.update("DELETE FROM archive_event WHERE day >= ?", today.toString());
        for (JsonNode e : events) {
            String start = e.path("start_date_local").asString();
            if (e.path("id").isNumber() && start.length() >= 10) {
                jdbc.update("INSERT OR REPLACE INTO archive_event (id, day, json, fetched_at) VALUES (?, ?, ?, ?)",
                        e.path("id").asLong(), start.substring(0, 10), e.toString(), now);
            }
        }
    }

    /**
     * What is planned from {@code from} on, one line per entry, from the cache: day, sport, name,
     * planned duration, and whether race-radio wrote it. Notes and races are included.
     */
    public List<String> planned(LocalDate from, int days) {
        return jdbc.queryForList("SELECT day, json_extract(json, '$.category') AS cat, json_extract(json, '$.type') AS type, "
                        + "json_extract(json, '$.name') AS name, json_extract(json, '$.moving_time') AS t, "
                        + "json_extract(json, '$.external_id') AS ext FROM archive_event WHERE day >= ? AND day < ? ORDER BY day",
                from.toString(), from.plusDays(days).toString()).stream()
                .filter(r -> !"NOTE".equals(r.get("cat")) || !String.valueOf(r.get("ext")).startsWith("race-radio-"))
                .map(r -> r.get("day") + " " + r.get("cat") + (r.get("type") == null ? "" : " " + r.get("type"))
                        + " « " + r.get("name") + " »" + (r.get("t") instanceof Number n && n.intValue() > 0 ? " " + n.intValue() / 60 + " min" : "")
                        + (String.valueOf(r.get("ext")).startsWith("race-radio-") ? " (race-radio)" : ""))
                .toList();
    }

    /** The workouts planned on one day, as the calendar cache holds them, whoever wrote them. */
    public List<io.github.pharaphara.raceradio.icu.model.CalendarEvent> workoutsOn(LocalDate day) {
        return jdbc.queryForList("SELECT json FROM archive_event WHERE day = ? AND json_extract(json, '$.category') = 'WORKOUT' ORDER BY id",
                        String.class, day.toString()).stream()
                .map(j -> EVENTS.readValue(j, io.github.pharaphara.raceradio.icu.model.CalendarEvent.class))
                .toList();
    }

    /** One planned workout from the calendar cache, by its id. */
    public Optional<io.github.pharaphara.raceradio.icu.model.CalendarEvent> workoutEvent(long id) {
        return jdbc.queryForList("SELECT json FROM archive_event WHERE id = ?", String.class, id).stream().findFirst()
                .map(j -> EVENTS.readValue(j, io.github.pharaphara.raceradio.icu.model.CalendarEvent.class));
    }

    /**
     * This morning against the seven before, from the wellness archive: heart-rate variability,
     * resting heart rate and sleep. Empty when today is not archived yet.
     */
    public Optional<String> morning() {
        return morning(LocalDate.now(clock));
    }

    /** That day's morning against the seven before it. */
    public Optional<String> morning(LocalDate today) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT day, json_extract(json, '$.hrv') AS hrv, "
                + "json_extract(json, '$.restingHR') AS rhr, json_extract(json, '$.sleepSecs') AS sleep "
                + "FROM archive_wellness WHERE day BETWEEN ? AND ? ORDER BY day", today.minusDays(7).toString(), today.toString());
        Map<String, Object> now = rows.stream().filter(r -> today.toString().equals(r.get("day"))).findFirst().orElse(null);
        if (now == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> before = rows.stream().filter(r -> !today.toString().equals(r.get("day"))).toList();
        StringBuilder b = new StringBuilder("morning of " + today + ":");
        appendAgainst(b, "HRV", now.get("hrv"), before, "hrv", 1, " ms");
        appendAgainst(b, "resting HR", now.get("rhr"), before, "rhr", 1, " bpm");
        appendAgainst(b, "sleep", now.get("sleep"), before, "sleep", 3600, " h");
        return Optional.of(b.toString());
    }

    private static void appendAgainst(StringBuilder b, String label, Object value, List<Map<String, Object>> before,
                                      String key, double scale, String unit) {
        if (!(value instanceof Number v)) {
            return;
        }
        double avg = before.stream().map(r -> r.get(key)).filter(x -> x instanceof Number)
                .mapToDouble(x -> ((Number) x).doubleValue()).average().orElse(Double.NaN);
        b.append(String.format(java.util.Locale.ROOT, " %s %.1f%s", label, v.doubleValue() / scale, unit));
        if (!Double.isNaN(avg)) {
            b.append(String.format(java.util.Locale.ROOT, " (7-day average %.1f%s)", avg / scale, unit));
        }
        b.append(';');
    }

    /** An activity changed upstream (merged, or deleted by a merge): its local copies are dropped and redone at the next sync. */
    public void invalidate(String activityId) {
        streams.evict(activityId);
        jdbc.update("DELETE FROM computed_metric WHERE activity_id = ?", activityId);
        jdbc.update("DELETE FROM archive_activity WHERE id = ?", activityId);
    }

    /** The last days as one line per session, from the archive only: what a prescription is calibrated on. */
    public List<String> recent(int days) {
        LocalDate from = LocalDate.now(clock).minusDays(days);
        return jdbc.queryForList("SELECT day, type, json_extract(json, '$.name') AS name, json_extract(json, '$.moving_time') AS t, "
                        + "json_extract(json, '$.icu_training_load') AS load, json_extract(json, '$.icu_intensity') AS intensity, "
                        + "json_extract(json, '$.icu_rpe') AS rpe "
                        + "FROM archive_activity WHERE day >= ? ORDER BY day", from.toString()).stream()
                .map(r -> r.get("day") + " " + r.get("type") + " « " + r.get("name") + " » "
                        + (r.get("t") instanceof Number n ? n.intValue() / 60 + " min" : "")
                        + (r.get("load") instanceof Number l ? ", charge " + l.intValue() : "")
                        + (r.get("intensity") instanceof Number i ? ", intensité " + i.intValue() + " %" : "")
                        + (r.get("rpe") instanceof Number p && p.intValue() > 0 ? ", RPE Garmin " + p.intValue() + "/10" : ""))
                .toList();
    }

    public int activities() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM archive_activity", Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<LocalDate> lastSync() {
        String at = jdbc.queryForObject("SELECT MAX(fetched_at) FROM archive_activity", String.class);
        return at == null ? Optional.empty() : Optional.of(LocalDate.ofInstant(java.time.Instant.parse(at), clock.getZone()));
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }
}
