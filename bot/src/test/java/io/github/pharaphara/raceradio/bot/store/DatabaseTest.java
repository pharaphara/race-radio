package io.github.pharaphara.raceradio.bot.store;

import io.github.pharaphara.raceradio.bot.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTest {

    @TempDir
    Path dir;

    private static final Database.Administrator ADMIN = new Database.Administrator(42, "i42");

    private JdbcTemplate jdbc(Path file) {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite:" + file + "?foreign_keys=true", true));
    }

    /** A database as race-radio wrote it before athletes, with a little of everything. */
    private Path before() {
        Path file = dir.resolve("race-radio.db");
        JdbcTemplate old = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite:" + file, true));
        new ResourceDatabasePopulator(new ClassPathResource("schema-before-athletes.sql")).execute(old.getDataSource());
        old.update("INSERT INTO note (at, text) VALUES ('2026-10-01T08:00:00Z', 'Trifox X10, GP5000')");
        old.update("INSERT INTO note (at, text, active) VALUES ('2026-10-02T08:00:00Z', 'retirée', 0)");
        old.update("INSERT INTO objective (kind, sport, priority, target, at) VALUES ('FITNESS', 'Ride', 'A', 'forme', '2026-10-01T08:00:00Z')");
        old.update("INSERT INTO setting (key, value) VALUES ('sports', 'Swim,Ride,Run'), ('strava.refresh_token', 'r')");
        old.update("INSERT INTO feel (day, sleep, energy, soreness, mood, appetite, at) VALUES ('2026-10-19', 4, 3, 4, 4, 5, 'x')");
        old.update("INSERT INTO reviewed_activity (activity_id, at) VALUES ('i197980854', 'x')");
        old.update("INSERT INTO archive_activity (id, day, type, json, fetched_at) VALUES ('i197980854', '2026-10-16', 'Ride', '{}', 'x')");
        old.update("INSERT INTO computed_metric (activity_id, metric, value) VALUES ('i197980854', 'hr_max_30s', 183)");
        old.update("INSERT INTO llm_call (at, purpose, requested_model, prompt_version) VALUES ('x', 'REVIEW', 'claude', 'v')");
        old.update("INSERT INTO strava_segment (id, name, detail) VALUES (101, 'Col de Perjuret', '{}'), (-1, 'Montée du col', '{}')");
        old.update("INSERT INTO strava_effort (id, segment_id, activity_id, day, elapsed_seconds, json) VALUES (9, 101, 5, '2026-09-22', 600, '{}')");
        old.update("INSERT INTO local_effort (activity_id, segment_id, start_index, day, seconds) VALUES ('i197980854', -1, 10, '2026-10-16', 301.5)");
        return file;
    }

    @Test
    void aFreshDatabaseGetsTheSchemaAndTheAdministrator() {
        Database.Outcome o = Database.prepare(dir.resolve("new.db"), ADMIN, false);
        assertThat(o.action()).isEqualTo("created");
        JdbcTemplate j = jdbc(dir.resolve("new.db"));
        assertThat(j.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(Database.VERSION);
        assertThat(j.queryForMap("SELECT id, chat_id, role, intervals_athlete_id FROM athlete"))
                .containsEntry("id", 1).containsEntry("chat_id", 42).containsEntry("role", "admin").containsEntry("intervals_athlete_id", "i42");
        assertThat(Database.prepare(dir.resolve("new.db"), ADMIN, false).action()).isEqualTo("current");
    }

    @Test
    void aDatabaseFromBeforeAthletesIsRefusedUntouchedWithoutAReset() {
        Path file = before();
        assertThatThrownBy(() -> Database.prepare(file, ADMIN, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("--race-radio.once=reset");
        assertThat(jdbc(file).queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(2);
        assertThat(Files.exists(file.resolveSibling("race-radio.db" + Database.SET_ASIDE))).isFalse();
    }

    @Test
    void aResetKeepsTheOldFileAndCopiesEveryRowToTheAdministrator() {
        Path file = before();
        Database.Outcome o = Database.prepare(file, ADMIN, true);
        assertThat(Files.exists(file.resolveSibling("race-radio.db" + Database.SET_ASIDE))).isTrue();
        assertThat(o.copied()).allSatisfy((table, n) -> assertThat(n[1]).as(table).isEqualTo(n[0]));
        assertThat(o.copied().get("note")).containsExactly(2, 2);

        JdbcTemplate j = jdbc(file);
        assertThat(j.queryForList("SELECT DISTINCT athlete_id FROM note", Long.class)).containsExactly(1L);
        assertThat(j.queryForObject("SELECT text FROM note WHERE active = 1", String.class)).isEqualTo("Trifox X10, GP5000");
        assertThat(j.queryForObject("SELECT value FROM setting WHERE athlete_id = 1 AND key = 'sports'", String.class)).isEqualTo("Swim,Ride,Run");
        assertThat(j.queryForObject("SELECT value FROM computed_metric WHERE athlete_id = 1", Double.class)).isEqualTo(183.0);
        assertThat(j.queryForObject("SELECT COUNT(*) FROM reviewed_activity WHERE athlete_id = 1", Integer.class)).isEqualTo(1);
        // The segments move to the shared library, with where they came from; the efforts stay the athlete's.
        assertThat(j.queryForList("SELECT id, origin, added_by FROM segment ORDER BY id"))
                .containsExactly(Map.of("id", -1, "origin", "custom", "added_by", 1), Map.of("id", 101, "origin", "strava", "added_by", 1));
        assertThat(j.queryForObject("SELECT athlete_id FROM strava_effort", Long.class)).isEqualTo(1L);
        assertThat(j.queryForObject("SELECT seconds FROM local_effort WHERE athlete_id = 1", Double.class)).isEqualTo(301.5);
        // From now on the database is current.
        assertThat(Database.prepare(file, ADMIN, false).action()).isEqualTo("current");
    }

    @Test
    void aResetIsNeverMadeTwice() throws Exception {
        Path file = before();
        Files.writeString(file.resolveSibling("race-radio.db" + Database.SET_ASIDE), "an earlier attempt");
        assertThatThrownBy(() -> Database.prepare(file, ADMIN, true)).hasMessageContaining("already exists");
        assertThat(jdbc(file).queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(2);
    }

    @Test
    void removingAnAthleteRemovesEveryRowOfTheirsAndNothingElse() {
        AthleteDb admin = TestDb.in(dir);
        AthleteDb friend = TestDb.another(admin, 7);
        for (AthleteDb db : new AthleteDb[]{admin, friend}) {
            new Conversations(db).addNote(Instant.now(), "note de " + db.id());
            new Conversations(db).add(Instant.now(), "athlete", "bonjour");
            new FeelStore(db).save(java.time.LocalDate.of(2026, 10, 20), new io.github.pharaphara.raceradio.core.readiness.Feel(4, 4, 4, 4, 4), Instant.now());
            new ReviewedActivities(db).add("i1", Instant.now());
        }
        admin.jdbc().update("DELETE FROM athlete WHERE id = ?", friend.id());
        for (String table : Database.ATHLETE_TABLES) {
            assertThat(admin.jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE athlete_id = ?", Integer.class, friend.id()))
                    .as(table).isZero();
        }
        assertThat(new Conversations(admin).activeNotes()).extracting(Conversations.Note::text).containsExactly("note de 1");
    }

    @Test
    void anAthleteNeverReachesAnothersNotesOrObjectives() {
        AthleteDb admin = TestDb.in(dir);
        AthleteDb friend = TestDb.another(admin, 7);
        long mine = new Conversations(admin).addNote(Instant.now(), "à moi");
        assertThat(new Conversations(friend).activeNotes()).isEmpty();
        assertThat(new Conversations(friend).withdraw(mine)).isFalse();
        assertThat(new Conversations(admin).activeNotes()).hasSize(1);
        // The same activity can be reviewed for each of two athletes who did it together.
        new ReviewedActivities(admin).add("i9", Instant.now());
        assertThat(new ReviewedActivities(friend).contains("i9")).isFalse();
    }
}
