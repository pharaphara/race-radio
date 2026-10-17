package io.github.pharaphara.raceradio.bot.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Activities already reviewed, so a restart or an overlapping poll never reviews one twice. */
@Repository
public class ReviewedActivities {

    private final JdbcTemplate jdbc;

    public ReviewedActivities(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean contains(String activityId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reviewed_activity WHERE activity_id = ?", Integer.class, activityId);
        return n != null && n > 0;
    }

    public void add(String activityId, Instant at) {
        jdbc.update("INSERT OR IGNORE INTO reviewed_activity (activity_id, at) VALUES (?, ?)", activityId, at.toString());
    }
}
