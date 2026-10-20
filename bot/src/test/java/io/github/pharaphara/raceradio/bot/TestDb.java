package io.github.pharaphara.raceradio.bot;

import io.github.pharaphara.raceradio.bot.athlete.AthleteId;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import io.github.pharaphara.raceradio.bot.store.Database;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A real SQLite file with the real schema, in a temporary directory, prepared by {@link Database}
 * as in service, foreign keys on, with athlete 1 as the administrator.
 */
public final class TestDb {

    private TestDb() {
    }

    /** The administrator's view of a fresh database. */
    public static AthleteDb in(Path dir) {
        Path file = dir.resolve("test.db");
        Database.prepare(file, new Database.Administrator(42, "i42"), false);
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + file + "?foreign_keys=true", true);
        return new AthleteDb(new JdbcTemplate(ds), new AthleteId(1));
    }

    /** One more athlete in the same database, to check that two never see each other's data. */
    public static AthleteDb another(AthleteDb db, long chatId) {
        Long id = db.jdbc().queryForObject("INSERT INTO athlete (chat_id, role, created_at) VALUES (?, 'athlete', ?) RETURNING id",
                Long.class, chatId, Instant.now().toString());
        return new AthleteDb(db.jdbc(), new AthleteId(id));
    }
}
