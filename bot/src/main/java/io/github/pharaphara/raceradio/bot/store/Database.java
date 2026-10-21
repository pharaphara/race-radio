package io.github.pharaphara.raceradio.bot.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings the database file to the current schema before anything else opens it.
 *
 * <p>It reads {@code user_version} first: a missing file gets the schema; a current one is left as
 * it is; a database from before athletes existed is refused with an explanation, unless the
 * administrator asked for the one-off {@code reset}. Then the old file is set aside, untouched, and
 * every one of its tables is copied into a fresh database, all rows to the administrator, the
 * segments into the shared library. Nothing is altered in place.
 */
public final class Database {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /** The schema version this program writes and reads. */
    public static final int VERSION = 1;
    /** Where the database from before athletes is kept after a reset. */
    public static final String SET_ASIDE = ".before-athletes";

    /** Tables of an athlete's data, copied as they are on a reset, all rows to the administrator. */
    static final List<String> ATHLETE_TABLES = List.of("llm_call", "decision", "feel", "reviewed_activity", "conversation",
            "note", "objective", "setting", "adaptation", "archive_activity", "archive_wellness", "computed_metric",
            "archive_event", "strava_activity", "strava_effort", "local_effort", "segment_scan");

    /** The administrator as the configuration knows them; empty values are left as they are. */
    public record Administrator(long chatId, String intervalsAthleteId) {
    }

    /** What preparing the database did, for the logs and for the one-off reset's report. */
    public record Outcome(String action, long administratorId, Map<String, long[]> copied) {

        public String report() {
            StringBuilder b = new StringBuilder(action).append(", administrator is athlete ").append(administratorId);
            copied.forEach((table, n) -> b.append('\n').append(table).append(": ")
                    .append(n[0]).append(" rows before, ").append(n[1]).append(" after"));
            return b.toString();
        }
    }

    private Database() {
    }

    /**
     * @param reset  true only for the one-off move from a database without athletes
     * @throws IllegalStateException when the file predates athletes and no reset was asked, or was
     *                               written by a newer version of this program
     */
    public static Outcome prepare(Path file, Administrator admin, boolean reset) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            int version;
            boolean empty;
            try (Connection c = open(file)) {
                version = userVersion(c);
                empty = tables(c, "main").isEmpty();
            }
            if (version > VERSION) {
                throw new IllegalStateException("the database at " + file + " has schema version " + version
                        + ", newer than this program's " + VERSION + ": update the program");
            }
            if (version == 0 && !empty) {
                if (!reset) {
                    throw new IllegalStateException("the database at " + file + " predates athletes. Stop the service "
                            + "and run it once with --race-radio.once=reset: the file is set aside as it is and every "
                            + "row is copied into a new database, to the administrator.");
                }
                return moveToAthletes(file, admin);
            }
            try (Connection c = open(file)) {
                applySchema(c);
                long id = ensureAdministrator(c, admin);
                return new Outcome(empty ? "created" : "current", id, Map.of());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("preparing the database at " + file + " failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Outcome moveToAthletes(Path file, Administrator admin) throws SQLException, IOException {
        try (Connection c = open(file); Statement s = c.createStatement()) {
            // Everything written in write-ahead mode goes back into the file before it is moved.
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        Path old = file.resolveSibling(file.getFileName() + SET_ASIDE);
        if (Files.exists(old)) {
            throw new IllegalStateException(old + " already exists: a reset was already made, or started. Look before deleting anything.");
        }
        Files.move(file, old, StandardCopyOption.ATOMIC_MOVE);
        for (String suffix : List.of("-wal", "-shm")) {
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + suffix));
        }
        Map<String, long[]> copied = new LinkedHashMap<>();
        long id;
        try (Connection c = open(file)) {
            applySchema(c);
            id = ensureAdministrator(c, admin);
            try (Statement s = c.createStatement()) {
                s.execute("ATTACH DATABASE '" + old.toAbsolutePath().toString().replace("'", "''") + "' AS old");
            }
            c.setAutoCommit(false);
            Set<String> oldTables = tables(c, "old");
            for (String table : ATHLETE_TABLES) {
                if (!oldTables.contains(table)) {
                    continue;
                }
                List<String> columns = new ArrayList<>(columns(c, "old", table));
                columns.retainAll(columns(c, "main", table));
                String list = String.join(", ", columns);
                try (PreparedStatement p = c.prepareStatement("INSERT INTO main." + table + " (athlete_id, " + list + ") SELECT ?, "
                        + list + " FROM old." + table)) {
                    p.setLong(1, id);
                    p.executeUpdate();
                }
                copied.put(table, new long[]{count(c, "old." + table), count(c, "main." + table)});
            }
            if (oldTables.contains("strava_segment")) {
                try (PreparedStatement p = c.prepareStatement("""
                        INSERT INTO main.segment (id, origin, name, detail, fetched_at, added_by)
                        SELECT id, CASE WHEN id < 0 THEN 'custom' ELSE 'strava' END, name, detail, fetched_at, ? FROM old.strava_segment""")) {
                    p.setLong(1, id);
                    p.executeUpdate();
                }
                copied.put("strava_segment > segment", new long[]{count(c, "old.strava_segment"), count(c, "main.segment")});
            }
            for (Map.Entry<String, long[]> e : copied.entrySet()) {
                if (e.getValue()[0] != e.getValue()[1]) {
                    c.rollback();
                    throw new IllegalStateException("copying " + e.getKey() + " gave " + e.getValue()[1] + " rows for "
                            + e.getValue()[0] + ": nothing was kept, the old database is at " + old);
                }
            }
            c.commit();
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("DETACH DATABASE old");
            }
        }
        LOG.info("database moved to athletes, the old file is kept at {}", old);
        return new Outcome("moved to athletes, the old file kept at " + old, id, copied);
    }

    static Connection open(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath() + "?foreign_keys=true");
    }

    /** The statements of {@code schema.sql}, comments left out; every one is idempotent. */
    static void applySchema(Connection c) throws SQLException {
        String text;
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream("schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql is missing from the program");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder code = new StringBuilder();
        for (String line : text.lines().toList()) {
            if (!line.strip().startsWith("--")) {
                code.append(line).append('\n');
            }
        }
        try (Statement s = c.createStatement()) {
            for (String statement : code.toString().split(";\\s*\\n")) {
                if (!statement.isBlank()) {
                    s.execute(statement.strip());
                }
            }
        }
    }

    /** The administrator's id: the one already there, brought up to date, or athlete 1, created. */
    static long ensureAdministrator(Connection c, Administrator admin) throws SQLException {
        Long chat = admin.chatId() == 0 ? null : admin.chatId();
        String intervals = admin.intervalsAthleteId() == null || admin.intervalsAthleteId().isBlank() ? null : admin.intervalsAthleteId();
        try (PreparedStatement q = c.prepareStatement("SELECT id FROM athlete WHERE role = 'admin' ORDER BY id LIMIT 1");
             ResultSet rs = q.executeQuery()) {
            if (rs.next()) {
                long id = rs.getLong(1);
                try (PreparedStatement u = c.prepareStatement("""
                        UPDATE athlete SET chat_id = COALESCE(?, chat_id), intervals_athlete_id = COALESCE(?, intervals_athlete_id)
                        WHERE id = ?""")) {
                    u.setObject(1, chat);
                    u.setObject(2, intervals);
                    u.setLong(3, id);
                    u.executeUpdate();
                }
                return id;
            }
        }
        try (PreparedStatement i = c.prepareStatement("""
                INSERT INTO athlete (id, chat_id, role, intervals_athlete_id, created_at) VALUES (1, ?, 'admin', ?, ?)""")) {
            i.setObject(1, chat);
            i.setObject(2, intervals);
            i.setString(3, Instant.now().toString());
            i.executeUpdate();
        }
        return 1;
    }

    private static int userVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Set<String> tables(Connection c, String schema) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM " + schema + ".sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static List<String> columns(Connection c, String schema, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA " + schema + ".table_info(" + table + ")")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
