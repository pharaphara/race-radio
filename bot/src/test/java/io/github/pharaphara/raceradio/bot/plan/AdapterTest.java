package io.github.pharaphara.raceradio.bot.plan;

import com.sun.net.httpserver.HttpServer;
import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.core.readiness.Verdict;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a local stand-in for Intervals.icu, in live mode, recording every request. */
class AdapterTest {

    private static final Clock MORNING = Clock.fixed(Instant.parse("2026-11-18T06:00:00Z"), ZoneId.of("Europe/Paris"));
    private static final String DOC = "{\"steps\":[{\"duration\":600,\"power\":{\"start\":120,\"end\":150,\"units\":\"w\"}}]}";
    private static final String EVENTS = """
            [{"id":11,"start_date_local":"2026-11-18T00:00:00","category":"WORKOUT","type":"Ride","name":"Vélo seuil · 3 × 8 min",
              "description":"Échauffement\\n- 15m 125-175w","external_id":"race-radio-a","moving_time":3600,"workout_doc":%s},
             {"id":12,"start_date_local":"2026-11-18T00:00:00","category":"WORKOUT","type":"Run","name":"Course facile · 40 min",
              "external_id":"race-radio-b","moving_time":2400,"workout_doc":%s},
             {"id":13,"start_date_local":"2026-11-18T00:00:00","category":"WORKOUT","type":"Ride","name":"Garmin Coach VO2",
              "external_id":"garmin-x","moving_time":3600,"workout_doc":%s}]""".formatted(DOC, DOC, DOC);

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private Adapter adapter;
    private Adapter live;
    private Plans plans;
    private final List<String> journal = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", ex -> {
            String path = ex.getRequestURI().getPath().substring("/api/v1/".length());
            String m = ex.getRequestMethod();
            requests.add(m + " " + path + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery())
                    + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body;
            if (m.equals("GET") && path.equals("athlete/i42")) {
                body = "{\"id\":\"i42\",\"icu_garmin_upload_workouts\":true,\"sportSettings\":[{\"types\":[\"Ride\"],\"ftp\":250},{\"types\":[\"Run\"],\"threshold_pace\":3.7}]}";
            } else if (m.equals("GET") && path.equals("athlete/i42/events")) {
                body = EVENTS;
            } else if (m.equals("GET") && path.startsWith("athlete/i42/events/")) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                String ext = id.equals("13") ? "garmin-x" : "race-radio-" + id;
                body = "{\"id\":" + id + ",\"external_id\":\"" + ext + "\",\"workout_doc\":" + DOC + "}";
            } else if (m.equals("POST")) {
                body = "{\"id\":99,\"external_id\":\"race-radio-new\"}";
            } else {
                body = "{}";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        IntervalsClient icu = new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/"));
        JdbcTemplate jdbc = TestDb.in(dir);
        plans = new Plans(jdbc);
        IntervalsWriter dry = new IntervalsWriter(icu, IntervalsWriter.Mode.DRY_RUN, journal::add);
        adapter = new Adapter(icu, dry, plans, new Planner(icu, dry, plans, null, 5, MORNING), jdbc, MORNING);
        IntervalsWriter liveWriter = new IntervalsWriter(icu, IntervalsWriter.Mode.LIVE, s -> { });
        live = new Adapter(icu, liveWriter, plans, new Planner(icu, liveWriter, plans, null, 5, MORNING), jdbc, MORNING);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aYellowMorningLightensOnlyTheCoachsOwnHardWorkout() {
        var r = adapter.adapt(Verdict.YELLOW).orElseThrow();
        assertThat(r.summary()).contains("Vélo seuil").contains("Vélo endurance").doesNotContain("Garmin").doesNotContain("Course facile");
        assertThat(journal).anyMatch(q -> q.startsWith("DRY RUN DELETE athlete/i42/events/11?others=false"));
        assertThat(journal).noneMatch(q -> q.contains("events/12") || q.contains("events/13"));
    }

    @Test
    void aRedMorningTurnsTheBikeIntoRecovery() {
        assertThat(adapter.adapt(Verdict.RED).orElseThrow().summary()).contains("Vélo récupération").contains("Footing récupération");
    }

    @Test
    void inAnalysisModeNothingIsTouched() {
        plans.mode(Plans.Mode.ANALYSE);
        assertThat(adapter.adapt(Verdict.RED)).isEmpty();
        assertThat(requests).isEmpty();
        assertThat(journal).isEmpty();
    }

    @Test
    void undoingPutsTheOriginalBack() {
        var r = adapter.adapt(Verdict.YELLOW).orElseThrow();
        journal.clear();
        assertThat(adapter.undo(r.batch())).contains("Vélo seuil");
        assertThat(journal).anyMatch(q -> q.startsWith("DRY RUN POST athlete/i42/events") && q.contains("Vélo seuil"));
        assertThat(adapter.undo(r.batch())).isEqualTo("Rien à annuler.");
    }

    @Test
    void ifTheCalendarMisreadsTheReplacementTheOriginalStays() {
        // The stand-in returns a one-step reading for every workout, so the replacement is refused.
        var r = live.adapt(Verdict.YELLOW).orElseThrow();
        assertThat(r.summary()).contains("laissée telle quelle");
        assertThat(requests).noneMatch(q -> q.startsWith("DELETE athlete/i42/events/11"));
    }
}
