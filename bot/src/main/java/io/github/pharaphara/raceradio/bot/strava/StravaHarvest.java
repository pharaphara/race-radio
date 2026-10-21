package io.github.pharaphara.raceradio.bot.strava;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Copies the athlete's Strava segments into the local archive, before the subscription that gives
 * access to them ends: every activity, every segment crossed in it (all of them, not only starred
 * ones), and each segment's definition with its GPS trace, which is what makes later matching
 * possible without Strava.
 *
 * <p>Runs in small steps within the read quota and resumes where it stopped: activities first,
 * then their details, then the segments. A step that hits the quota simply waits for the next run.
 */
public class StravaHarvest {

    private static final Logger LOG = LoggerFactory.getLogger(StravaHarvest.class);
    static final int PAGE = 200;

    /** Where the harvest stands. */
    public record Progress(int activities, int detailed, int segments, int segmentsWithTrace, int efforts,
                           Instant pausedUntil) {

        public boolean done() {
            return activities > 0 && detailed == activities && segmentsWithTrace == segments;
        }
    }

    private final StravaClient strava;
    private final int pageSize;
    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;
    private final Clock clock;
    private volatile Instant pausedUntil = Instant.EPOCH;
    private volatile Instant lastListed = Instant.EPOCH;
    /** New activities are looked for this often; detailing and segments go on at every run. */
    static final java.time.Duration LIST_EVERY = java.time.Duration.ofHours(6);

    public StravaHarvest(StravaClient strava, AthleteDb db, Clock clock) {
        this(strava, db, clock, PAGE);
    }

    StravaHarvest(StravaClient strava, AthleteDb db, Clock clock, int pageSize) {
        this.pageSize = pageSize;
        this.strava = strava;
        this.jdbc = db.jdbc();
        this.athlete = db.id();
        this.clock = clock;
    }

    /**
     * One step: lists new activities, then details them, then fetches the segments, until the quota
     * says stop. Cheap when everything is already harvested: one list call.
     */
    public synchronized Progress run() {
        if (!strava.configured() || clock.instant().isBefore(pausedUntil) || ended().isPresent()) {
            return progress();
        }
        try {
            if (clock.instant().isAfter(lastListed.plus(LIST_EVERY))) {
                listActivities();
                lastListed = clock.instant();
            }
            detailActivities();
            fetchSegments();
        } catch (StravaClient.QuotaReached e) {
            pausedUntil = e.retryAfter();
            LOG.info("strava harvest paused until {}", pausedUntil);
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            if (m.startsWith("Strava 402") || m.startsWith("Strava 403")) {
                // The subscription that opens segment efforts is over: stop asking, local timing carries on.
                setting("strava.ended", java.time.LocalDate.ofInstant(clock.instant(), clock.getZone()).toString());
                LOG.info("strava refuses segment efforts ({}): harvest stopped, segments are timed locally", m);
            } else {
                LOG.warn("strava harvest step failed: {}", m);
            }
        }
        return progress();
    }

    /** The day Strava stopped giving segment efforts, if it did. */
    public java.util.Optional<java.time.LocalDate> ended() {
        return jdbc.queryForList("SELECT value FROM setting WHERE athlete_id = ? AND key = 'strava.ended'", String.class, athlete).stream()
                .findFirst().map(java.time.LocalDate::parse);
    }

    /**
     * Pages back through the athlete's activities. Until the whole history has been listed once, it
     * goes to the last page, page after page across runs if the quota cuts it; after that, it stops
     * at the first page holding nothing new.
     */
    void listActivities() {
        String now = clock.instant().toString();
        boolean complete = !jdbc.queryForList("SELECT value FROM setting WHERE athlete_id = ? AND key = 'strava.listed_all'", String.class, athlete).isEmpty();
        int startPage = complete ? 1 : jdbc.queryForList("SELECT value FROM setting WHERE athlete_id = ? AND key = 'strava.next_page'", String.class, athlete)
                .stream().findFirst().map(Integer::parseInt).orElse(1);
        for (int page = startPage; ; page++) {
            JsonNode list = strava.get("athlete/activities?per_page=" + pageSize + "&page=" + page);
            if (!list.isArray() || list.isEmpty()) {
                setting("strava.listed_all", "true");
                return;
            }
            int added = 0;
            for (JsonNode a : list) {
                added += jdbc.update("INSERT OR IGNORE INTO strava_activity (athlete_id, id, day, sport, summary, fetched_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        athlete, a.path("id").asLong(), a.path("start_date_local").asString().substring(0, 10),
                        a.path("sport_type").asString(), a.toString(), now);
            }
            if (list.size() < pageSize) {
                setting("strava.listed_all", "true");
                return;
            }
            if (complete && added == 0) {
                return;
            }
            if (!complete) {
                setting("strava.next_page", String.valueOf(page + 1));
            }
        }
    }

    private void setting(String key, String value) {
        jdbc.update("INSERT INTO setting (athlete_id, key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT(athlete_id, key) DO UPDATE SET value = excluded.value", athlete, key, value);
    }

    /** Fetches each activity with every segment effort in it, newest first. */
    void detailActivities() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM strava_activity WHERE athlete_id = ? AND detail IS NULL ORDER BY day DESC",
                Long.class, athlete);
        for (long id : ids) {
            JsonNode a = strava.get("activities/" + id + "?include_all_efforts=true");
            for (JsonNode e : a.path("segment_efforts")) {
                JsonNode seg = e.path("segment");
                // The segment joins the shared library; the effort on it stays the athlete's own.
                jdbc.update("INSERT OR IGNORE INTO segment (id, origin, name, added_by) VALUES (?, 'strava', ?, ?)",
                        seg.path("id").asLong(), seg.path("name").asString(), athlete);
                jdbc.update("INSERT OR REPLACE INTO strava_effort (athlete_id, id, segment_id, activity_id, day, elapsed_seconds, "
                                + "moving_seconds, average_watts, average_hr, pr_rank, json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        athlete, e.path("id").asLong(), seg.path("id").asLong(), id, e.path("start_date_local").asString().substring(0, 10),
                        e.path("elapsed_time").asInt(), e.path("moving_time").isNumber() ? e.path("moving_time").asInt() : null,
                        e.path("average_watts").isNumber() ? e.path("average_watts").asDouble() : null,
                        e.path("average_heartrate").isNumber() ? e.path("average_heartrate").asDouble() : null,
                        e.path("pr_rank").isNumber() ? e.path("pr_rank").asInt() : null, e.toString());
            }
            jdbc.update("UPDATE strava_activity SET detail = ?, fetched_at = ? WHERE athlete_id = ? AND id = ?",
                    a.toString(), clock.instant().toString(), athlete, id);
        }
    }

    /** Fetches each segment's definition, with its trace, most ridden first. */
    void fetchSegments() {
        // Library segments still without a trace, those this athlete crossed most often first.
        List<Long> ids = jdbc.queryForList("SELECT s.id FROM segment s WHERE s.origin = 'strava' AND s.detail IS NULL "
                + "ORDER BY (SELECT COUNT(*) FROM strava_effort e WHERE e.athlete_id = ? AND e.segment_id = s.id) DESC", Long.class, athlete);
        for (long id : ids) {
            JsonNode s = strava.get("segments/" + id);
            jdbc.update("UPDATE segment SET name = ?, detail = ?, fetched_at = ? WHERE id = ?",
                    s.path("name").asString(), s.toString(), clock.instant().toString(), id);
        }
    }

    public Progress progress() {
        return new Progress(count("SELECT COUNT(*) FROM strava_activity WHERE athlete_id = ?", athlete),
                count("SELECT COUNT(*) FROM strava_activity WHERE athlete_id = ? AND detail IS NOT NULL", athlete),
                count("SELECT COUNT(*) FROM segment WHERE origin = 'strava'"),
                count("SELECT COUNT(*) FROM segment WHERE origin = 'strava' AND detail IS NOT NULL"),
                count("SELECT COUNT(*) FROM strava_effort WHERE athlete_id = ?", athlete), pausedUntil);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
