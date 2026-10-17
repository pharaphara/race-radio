package io.github.pharaphara.raceradio.bot.store;

import io.github.pharaphara.raceradio.core.readiness.Feel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** What the athlete reported, one record per day; a later report the same day replaces the first. */
@Repository
public class FeelStore {

    private final JdbcTemplate jdbc;

    public FeelStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(LocalDate day, Feel f, Instant at) {
        jdbc.update("""
                INSERT INTO feel (day, sleep, energy, soreness, mood, appetite, at) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(day) DO UPDATE SET sleep = excluded.sleep, energy = excluded.energy,
                    soreness = excluded.soreness, mood = excluded.mood, appetite = excluded.appetite, at = excluded.at""",
                day.toString(), f.sleep(), f.energy(), f.soreness(), f.mood(), f.appetite(), at.toString());
    }

    public Optional<Feel> on(LocalDate day) {
        return jdbc.query("SELECT sleep, energy, soreness, mood, appetite FROM feel WHERE day = ?",
                (rs, i) -> new Feel(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)),
                day.toString()).stream().findFirst();
    }

    /**
     * Reports of the days just before {@code day}, oldest first, stopping at the first day without
     * one: "three days running" must mean three consecutive days, not three reports in a week.
     */
    public List<Feel> before(LocalDate day, int days) {
        java.util.ArrayList<Feel> out = new java.util.ArrayList<>();
        for (int i = 1; i <= days; i++) {
            Optional<Feel> f = on(day.minusDays(i));
            if (f.isEmpty()) {
                break;
            }
            out.addFirst(f.get());
        }
        return out;
    }
}
