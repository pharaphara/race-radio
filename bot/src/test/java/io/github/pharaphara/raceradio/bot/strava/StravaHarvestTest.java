package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StravaHarvestTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    /** A Strava with two activities crossing one shared segment, and a quota of {@code budget} reads. */
    private static final class FakeStrava extends StravaClient {
        final List<String> calls = new ArrayList<>();
        int budget;

        FakeStrava(JdbcTemplate jdbc, int budget) {
            super("1", "s", "r", jdbc, Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
            this.budget = budget;
        }

        @Override
        public JsonNode get(String path) {
            if (budget-- <= 0) {
                throw new QuotaReached(Instant.parse("2026-10-18T10:15:00Z"));
            }
            calls.add(path);
            if (path.startsWith("athlete/activities")) {
                return JSON.readTree(path.contains("page=1") ? """
                        [{"id":2,"start_date_local":"2026-10-16T14:00:00Z","sport_type":"Ride"},
                         {"id":1,"start_date_local":"2026-09-01T09:00:00Z","sport_type":"Ride"}]""" : "[]");
            }
            if (path.startsWith("activities/")) {
                long id = Long.parseLong(path.substring(11, path.indexOf('?')));
                int t = id == 1 ? 1200 : 1176;
                return JSON.readTree("""
                        {"id":%d,"segment_efforts":[{"id":%d,"elapsed_time":%d,"moving_time":%d,"average_watts":250.0,
                         "average_heartrate":160.0,"pr_rank":null,"start_date_local":"%s",
                         "segment":{"id":18030145,"name":"Gorges de la Jonte"}}]}""".formatted(id, 100 + id, t, t,
                        id == 1 ? "2026-09-01T09:30:00Z" : "2026-10-16T14:30:00Z"));
            }
            return JSON.readTree("{\"id\":18030145,\"name\":\"Gorges de la Jonte\",\"map\":{\"polyline\":\"abc\"}}");
        }

        @Override
        public boolean configured() {
            return true;
        }
    }

    @Test
    void aHarvestStoppedByTheQuotaResumesWhereItStoppedAndEndsWithEveryTrace() {
        JdbcTemplate jdbc = TestDb.in(dir);
        FakeStrava strava = new FakeStrava(jdbc, 2);
        Clock early = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);
        StravaHarvest h = new StravaHarvest(strava, jdbc, early);

        StravaHarvest.Progress first = h.run();
        assertThat(first.activities()).isEqualTo(2);
        assertThat(first.detailed()).isEqualTo(1);
        assertThat(first.done()).isFalse();
        assertThat(first.pausedUntil()).isEqualTo(Instant.parse("2026-10-18T10:15:00Z"));

        strava.budget = 10;
        StravaHarvest later = new StravaHarvest(strava, jdbc, Clock.fixed(Instant.parse("2026-10-18T10:16:00Z"), ZoneOffset.UTC));
        StravaHarvest.Progress done = later.run();
        assertThat(done.done()).isTrue();
        assertThat(done.efforts()).isEqualTo(2);
        assertThat(done.segmentsWithTrace()).isEqualTo(1);
        // Activity 2 was detailed in the first run and never fetched again.
        assertThat(strava.calls.stream().filter(c -> c.startsWith("activities/2"))).hasSize(1);
    }

    @Test
    void theFirstListingGoesToTheLastPageEvenWhenTheQuotaCutsItBetweenRuns() {
        JdbcTemplate jdbc = TestDb.in(dir);
        List<String> pages = new ArrayList<>();
        int[] budget = {1};
        StravaClient pager = new StravaClient("1", "s", "r", jdbc, Clock.systemUTC()) {
            @Override
            public JsonNode get(String path) {
                if (budget[0]-- <= 0) {
                    throw new QuotaReached(Instant.EPOCH);
                }
                pages.add(path);
                int page = Integer.parseInt(path.substring(path.indexOf("page=", path.indexOf("per_page") + 9) + 5));
                // Two full pages of one activity, then an empty one.
                return JSON.readTree(page > 2 ? "[]" : "[{\"id\":" + page + ",\"start_date_local\":\"2026-01-0" + page + "T09:00:00Z\",\"sport_type\":\"Run\"}]");
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new StravaHarvest(pager, jdbc, Clock.systemUTC(), 1).listActivities())
                .isInstanceOf(StravaClient.QuotaReached.class);
        assertThat(pages).containsExactly("athlete/activities?per_page=1&page=1");

        budget[0] = 10;
        new StravaHarvest(pager, jdbc, Clock.systemUTC(), 1).listActivities();
        assertThat(pages).endsWith("athlete/activities?per_page=1&page=2", "athlete/activities?per_page=1&page=3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM strava_activity", Integer.class)).isEqualTo(2);

        // Listed once to the end: next time, the first page without anything new is enough.
        budget[0] = 10;
        pages.clear();
        new StravaHarvest(pager, jdbc, Clock.systemUTC(), 1).listActivities();
        assertThat(pages).containsExactly("athlete/activities?per_page=1&page=1");
    }

    @Test
    void aSegmentsHistoryNamesTheRecordAndTheGapOfTheLastPassage() {
        String text = Segments.history("Gorges de la Jonte", List.of(
                new Segments.Effort(LocalDate.of(2026, 7, 25), 1176, 260.0, 165.0),
                new Segments.Effort(LocalDate.of(2026, 10, 16), 1201, 248.0, 162.0)));
        assertThat(text).contains("2 passages archivés, record 19:36 le 25 juil. 26, 260 W, FC 165")
                .contains("Dernier passage le 16 oct. 26 : 20:01 (+25 s sur le record)");
    }
}
