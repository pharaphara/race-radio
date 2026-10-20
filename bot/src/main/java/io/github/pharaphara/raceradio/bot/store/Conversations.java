package io.github.pharaphara.raceradio.bot.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory in two layers, kept apart on purpose. Notes are current facts the athlete asked the coach
 * to keep (an injury, a constraint); they stay until withdrawn. The conversation is the dated record
 * of what was said; only its last turns are given back to the model.
 */
public class Conversations {

    public record Turn(String at, String role, String text) {
    }

    public record Note(long id, String at, String text) {
    }

    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;

    public Conversations(AthleteDb db) {
        this.jdbc = db.jdbc();
        this.athlete = db.id();
    }

    public void add(Instant at, String role, String text) {
        jdbc.update("INSERT INTO conversation (athlete_id, at, role, text) VALUES (?, ?, ?, ?)", athlete, at.toString(), role, text);
    }

    /** The last {@code n} turns, oldest first. */
    public List<Turn> last(int n) {
        List<Turn> t = new ArrayList<>(jdbc.query("SELECT at, role, text FROM conversation WHERE athlete_id = ? ORDER BY id DESC LIMIT ?",
                (rs, i) -> new Turn(rs.getString(1), rs.getString(2), rs.getString(3)), athlete, n));
        java.util.Collections.reverse(t);
        return t;
    }

    public long addNote(Instant at, String text) {
        Long id = jdbc.queryForObject("INSERT INTO note (athlete_id, at, text) VALUES (?, ?, ?) RETURNING id", Long.class,
                athlete, at.toString(), text);
        return id == null ? 0 : id;
    }

    public List<Note> activeNotes() {
        return jdbc.query("SELECT id, at, text FROM note WHERE athlete_id = ? AND active = 1 ORDER BY id",
                (rs, i) -> new Note(rs.getLong(1), rs.getString(2), rs.getString(3)), athlete);
    }

    public boolean withdraw(long id) {
        // The athlete is part of the condition: a number typed at random never reaches another's note.
        return jdbc.update("UPDATE note SET active = 0 WHERE athlete_id = ? AND id = ? AND active = 1", athlete, id) == 1;
    }
}
