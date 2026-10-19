package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LocalSegmentsTest {

    @TempDir
    Path dir;

    private LocalSegments segments(JdbcTemplate jdbc) {
        return new LocalSegments(jdbc, new SegmentLibrary(jdbc), null, Clock.systemUTC());
    }

    private static void strava(JdbcTemplate jdbc, long id, long segment, String day, int seconds) {
        jdbc.update("INSERT INTO strava_effort (id, segment_id, activity_id, day, elapsed_seconds, json) VALUES (?, ?, 1, ?, ?, '{}')",
                id, segment, day, seconds);
    }

    private static void local(JdbcTemplate jdbc, String activity, long segment, String day, double seconds) {
        jdbc.update("INSERT INTO local_effort (activity_id, segment_id, start_index, day, seconds) VALUES (?, ?, 0, ?, ?)",
                activity, segment, day, seconds);
    }

    @Test
    void stravaWinsOnItsDaysAndLocalTimesCarryOnAfter() {
        JdbcTemplate jdbc = TestDb.in(dir);
        strava(jdbc, 1, 7, "2026-09-01", 300);
        local(jdbc, "i1", 7, "2026-09-01", 301.4);
        local(jdbc, "i2", 7, "2027-05-01", 290.2);
        var history = segments(jdbc).history(7);
        assertThat(history).extracting(LocalSegments.Effort::day)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 5, 1));
        assertThat(history.getFirst().seconds()).isEqualTo(300);
    }

    @Test
    void aRecordIsSaidFirstThenAPlaceThenTheGap() {
        JdbcTemplate jdbc = TestDb.in(dir);
        jdbc.update("INSERT INTO strava_segment (id, name) VALUES (1, 'Col'), (2, 'Côte'), (3, 'Plat')");
        for (int i = 0; i < 4; i++) {
            strava(jdbc, 10 + i, 1, "2026-0" + (5 + i) + "-01", 600 + i);
            strava(jdbc, 20 + i, 2, "2026-0" + (5 + i) + "-01", 300 + 10 * i);
            strava(jdbc, 30 + i, 3, "2026-0" + (5 + i) + "-01", 100 + i);
        }
        local(jdbc, "i9", 3, "2026-10-16", 150);   // far behind
        local(jdbc, "i9", 1, "2026-10-16", 590);   // a record
        local(jdbc, "i9", 2, "2026-10-16", 305);   // second best
        var lines = segments(jdbc).notable("i9", 3);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("Col : 9:50, record (10 s de mieux que 10:00)");
        assertThat(lines.get(1)).startsWith("Côte : 5:05, 2e temps sur 5 passages");
        assertThat(lines.get(2)).startsWith("Plat : 2:30, à 50 s du record");
    }
}
