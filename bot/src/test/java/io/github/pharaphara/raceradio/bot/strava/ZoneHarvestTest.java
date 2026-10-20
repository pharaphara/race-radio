package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

class ZoneHarvestTest {

    @TempDir
    Path dir;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Strava's explore: two segments per square, the same one found again in the next square. */
    private static final class Explore extends StravaClient {
        final List<String> calls = new ArrayList<>();
        int budget;

        Explore(AthleteDb db, int budget) {
            super("1", "s", "r", db, Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));
            this.budget = budget;
        }

        @Override
        public JsonNode get(String path) {
            if (budget-- <= 0) {
                throw new QuotaReached(Instant.parse("2026-10-21T10:15:00Z"));
            }
            calls.add(path);
            int n = calls.size();
            return JSON.readTree("""
                    {"segments":[{"id":%d,"name":"Montée %d"},{"id":%d,"name":"Descente %d"}]}""".formatted(1000 + n, n, 1001 + n, n));
        }

        @Override
        public boolean configured() {
            return true;
        }
    }

    @Test
    void aDiscIsCutIntoSquaresThatAllTouchIt() {
        var tiles = ZoneHarvest.tiles(44.18, 3.43, 20);
        // A disc of 20 km covers about 1,257 km², some 200 squares of 2.5 km; the squares cut by the edge add a few.
        assertThat(tiles.size()).isBetween(200, 260);
        for (JsonNode t : tiles) {
            assertThat(t.get(2).asDouble()).isGreaterThan(t.get(0).asDouble());
            assertThat(t.get(3).asDouble()).isGreaterThan(t.get(1).asDouble());
            assertThat(Math.abs(t.get(0).asDouble() - 44.18)).isLessThan(0.2);
        }
    }

    @Test
    void anAreaFillsTheSharedLibraryAndResumesAfterTheQuota() {
        AthleteDb admin = TestDb.in(dir);
        Explore strava = new Explore(admin, 3);
        ZoneHarvest zone = new ZoneHarvest(strava, admin, Clock.systemUTC());
        int squares = zone.start(44.18, 3.43, 4, false);
        assertThat(squares).isGreaterThan(3);

        zone.step();
        assertThat(strava.calls).hasSize(3).allSatisfy(c -> assertThat(c).startsWith("segments/explore?bounds=").endsWith("activity_type=riding"));
        assertThat(zone.status()).contains("3 carrés explorés sur " + squares).contains("en cours");
        // Three squares, two segments each, one found twice: four new segments, Strava's, added by the administrator.
        assertThat(admin.jdbc().queryForObject("SELECT COUNT(*) FROM segment WHERE origin = 'strava' AND added_by = 1", Integer.class)).isEqualTo(4);

        strava.budget = 1000;
        zone.step();
        assertThat(strava.calls).hasSize(squares);
        assertThat(zone.status()).contains("terminée");
        assertThat(admin.jdbc().queryForObject("SELECT COUNT(*) FROM segment WHERE detail IS NULL", Integer.class)).isEqualTo(squares + 1);
    }

    @Test
    void eachAthleteSeesTheirOwnPassagesOnSharedSegmentsAndOnlyTheAdministratorHisStravaTimes() {
        AthleteDb admin = TestDb.in(dir);
        AthleteDb friend = TestDb.another(admin, 7);
        admin.jdbc().update("INSERT INTO segment (id, origin, name) VALUES (5, 'strava', 'Col de Perjuret')");
        admin.jdbc().update("INSERT INTO strava_effort (athlete_id, id, segment_id, activity_id, day, elapsed_seconds, json) "
                + "VALUES (1, 9, 5, 1, '2026-09-22', 600, '{}')");
        friend.jdbc().update("INSERT INTO local_effort (athlete_id, activity_id, segment_id, start_index, day, seconds) "
                + "VALUES (?, 'i9', 5, 0, '2026-10-20', 655.0)", friend.id());
        SegmentLibrary library = new SegmentLibrary(admin.jdbc());
        assertThat(new LocalSegments(friend, library, null, Clock.systemUTC()).history(5))
                .extracting(LocalSegments.Effort::day).containsExactly(LocalDate.of(2026, 10, 20));
        assertThat(new LocalSegments(admin, library, null, Clock.systemUTC()).history(5))
                .extracting(LocalSegments.Effort::day).containsExactly(LocalDate.of(2026, 9, 22));
    }
}
