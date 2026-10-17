package io.github.pharaphara.raceradio.bot.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory in two layers, kept apart on purpose. Notes are current facts the athlete asked the coach
 * to keep (an injury, a constraint); they stay until withdrawn. The conversation is the dated record
 * of what was said; only its last turns are given back to the model.
 */
@Repository
public class Conversations {

    public record Turn(String at, String role, String text) {
    }

    public record Note(long id, String at, String text) {
    }

    private final JdbcTemplate jdbc;

    public Conversations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(Instant at, String role, String text) {
        jdbc.update("INSERT INTO conversation (at, role, text) VALUES (?, ?, ?)", at.toString(), role, text);
    }

    /** The last {@code n} turns, oldest first. */
    public List<Turn> last(int n) {
        List<Turn> t = new ArrayList<>(jdbc.query("SELECT at, role, text FROM conversation ORDER BY id DESC LIMIT ?",
                (rs, i) -> new Turn(rs.getString(1), rs.getString(2), rs.getString(3)), n));
        java.util.Collections.reverse(t);
        return t;
    }

    public long addNote(Instant at, String text) {
        jdbc.update("INSERT INTO note (at, text) VALUES (?, ?)", at.toString(), text);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM note", Long.class);
        return id == null ? 0 : id;
    }

    public List<Note> activeNotes() {
        return jdbc.query("SELECT id, at, text FROM note WHERE active = 1 ORDER BY id",
                (rs, i) -> new Note(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public boolean withdraw(long id) {
        return jdbc.update("UPDATE note SET active = 0 WHERE id = ? AND active = 1", id) == 1;
    }
}
