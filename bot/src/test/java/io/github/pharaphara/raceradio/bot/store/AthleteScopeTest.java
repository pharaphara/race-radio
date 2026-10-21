package io.github.pharaphara.raceradio.bot.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No athlete's data without its athlete. Three checks, so that a table or a query added later cannot
 * quietly mix athletes up: the schema, the list of tables a reset copies, and every SQL statement
 * written in the code.
 */
class AthleteScopeTest {

    /** Tables that hold no athlete's data. */
    private static final Set<String> SHARED = Set.of("athlete", "invitation", "checkin", "segment");
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+(?:main\\.)?([a-z_]+)");

    @TempDir
    Path dir;

    private Set<String> athleteTables() throws SQLException {
        Database.prepare(dir.resolve("scope.db"), new Database.Administrator(1, "i1"), false);
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = Database.open(dir.resolve("scope.db")); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                if (!SHARED.contains(rs.getString(1))) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void everyTableOfAnAthletesDataStartsWithTheAthleteAndGoesWithThem() throws SQLException {
        Set<String> tables = athleteTables();
        assertThat(tables).hasSizeGreaterThanOrEqualTo(17);
        try (Connection c = Database.open(dir.resolve("scope.db")); Statement s = c.createStatement()) {
            for (String table : tables) {
                List<String> columns = new ArrayList<>();
                try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
                    while (rs.next()) {
                        columns.add(rs.getString("name"));
                    }
                }
                // Tables with their own counter keep it first; athlete_id comes right after.
                assertThat(columns.indexOf("athlete_id")).as(table).isBetween(0, 1);
                boolean cascades = false;
                try (ResultSet rs = s.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
                    while (rs.next()) {
                        cascades |= rs.getString("table").equals("athlete") && rs.getString("from").equals("athlete_id")
                                && rs.getString("on_delete").equals("CASCADE");
                    }
                }
                assertThat(cascades).as(table + " references athlete with ON DELETE CASCADE").isTrue();
            }
        }
    }

    @Test
    void aResetCopiesEveryTableOfTheSchemaAndNoOther() throws SQLException {
        assertThat(Database.ATHLETE_TABLES).containsExactlyInAnyOrderElementsOf(athleteTables());
    }

    @Test
    void everyStatementOnAnAthletesTableNamesTheAthlete() throws IOException, SQLException {
        Set<String> tables = athleteTables();
        List<String> missing = new ArrayList<>();
        Path main = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String sql : statements(Files.readString(file))) {
                    Matcher m = TABLE.matcher(sql);
                    while (m.find()) {
                        if (tables.contains(m.group(1).toLowerCase()) && !sql.contains("athlete_id")) {
                            missing.add(main.relativize(file) + ": " + sql.replaceAll("\\s+", " ").strip());
                            break;
                        }
                    }
                }
            }
        }
        assertThat(missing).as("SQL on an athlete's table without athlete_id").isEmpty();
    }

    /**
     * The SQL of each Java statement: its string literals, text blocks included, joined. Code between
     * literals is left out, so {@code "... WHERE " + m.filter() + " ..."} still reads as one statement.
     */
    static List<String> statements(String source) {
        List<String> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i) < 0 ? source.length() : source.indexOf('\n', i);
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i) + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                sql.append(source, i + 3, end).append(' ');
                i = end + 3;
            } else if (ch == '"') {
                int j = i + 1;
                while (source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                sql.append(source, i + 1, j).append(' ');
                i = j + 1;
            } else if (ch == '\'') {
                int j = i + 1;
                while (source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                if (ch == ';' || ch == '{' || ch == '}') {
                    if (!sql.isEmpty()) {
                        out.add(sql.toString());
                        sql.setLength(0);
                    }
                }
                i++;
            }
        }
        return out;
    }

    @Test
    void theStatementReaderJoinsLiteralsAndSkipsComments() {
        List<String> found = statements("""
                // SELECT * FROM note
                long n = jdbc.queryForObject("SELECT COUNT(*) FROM note "
                        + "WHERE athlete_id = ?" + x, Long.class);
                String s = \"\"\"
                        UPDATE feel SET sleep = 1\"\"\";
                """);
        assertThat(found).hasSize(2);
        assertThat(found.get(0)).contains("FROM note").contains("athlete_id");
        assertThat(found.get(1)).contains("UPDATE feel").doesNotContain("athlete_id");
    }
}
