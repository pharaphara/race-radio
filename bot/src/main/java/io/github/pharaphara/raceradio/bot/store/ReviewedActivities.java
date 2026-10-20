package io.github.pharaphara.raceradio.bot.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/** Activities already reviewed, so a restart or an overlapping poll never reviews one twice. */
public class ReviewedActivities {

    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;

    public ReviewedActivities(AthleteDb db) {
        this.jdbc = db.jdbc();
        this.athlete = db.id();
    }

    public boolean contains(String activityId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reviewed_activity WHERE athlete_id = ? AND activity_id = ?",
                Integer.class, athlete, activityId);
        return n != null && n > 0;
    }

    public void add(String activityId, Instant at) {
        jdbc.update("INSERT OR IGNORE INTO reviewed_activity (athlete_id, activity_id, at) VALUES (?, ?, ?)", athlete, activityId, at.toString());
    }
}
