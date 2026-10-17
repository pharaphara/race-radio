package io.github.pharaphara.raceradio.bot;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;

/** A real SQLite file with the real schema, in a temporary directory. */
public final class TestDb {

    private TestDb() {
    }

    public static JdbcTemplate in(Path dir) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("test.db"), true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new JdbcTemplate(ds);
    }
}
