package io.github.pharaphara.raceradio.bot.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Every decision with its evidence, so that "why?" can be answered months later. */
@Repository
public class DecisionLog {

    public record Decision(String at, String kind, String day, String verdict, String model,
                           String evidenceJson, String message, String promptVersion) {
    }

    private final JdbcTemplate jdbc;

    public DecisionLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Instant at, String kind, LocalDate day, String verdict, String model,
                       String evidenceJson, String message, String promptVersion) {
        jdbc.update("""
                INSERT INTO decision (at, kind, day, verdict, model, evidence_json, message, prompt_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                at.toString(), kind, day.toString(), verdict, model, evidenceJson, message, promptVersion);
    }

    public Optional<Decision> latest() {
        return jdbc.query("SELECT at, kind, day, verdict, model, evidence_json, message, prompt_version FROM decision ORDER BY id DESC LIMIT 1",
                (rs, i) -> new Decision(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))).stream().findFirst();
    }

    public boolean exists(String kind, LocalDate day) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM decision WHERE kind = ? AND day = ?", Integer.class, kind, day.toString());
        return n != null && n > 0;
    }
}
