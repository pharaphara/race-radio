package io.github.pharaphara.raceradio.icu;

import com.sun.net.httpserver.HttpServer;
import io.github.pharaphara.raceradio.icu.workout.SampleWorkouts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The writer against a local stand-in for Intervals.icu that records every request. */
class IntervalsWriterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final String UNDERSTOOD_DOC = """
            {"duration":3030,"steps":[
              {"duration":600,"power":{"start":120,"end":160,"units":"w"},"cadence":{"value":90,"units":"rpm"}},
              {"duration":1800,"reps":5,"steps":[
                 {"duration":180,"power":{"start":285,"end":300,"units":"w"}},
                 {"duration":180,"power":{"value":1,"units":"power_zone"}}]},
              {"duration":330,"power":{"value":1,"units":"power_zone"}}]}""";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private IntervalsClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", ex -> {
            String path = ex.getRequestURI().getPath().substring("/api/v1/".length());
            String query = ex.getRequestURI().getQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + (body.isEmpty() ? "" : " " + body));
            byte[] out = responses.getOrDefault(ex.getRequestMethod() + " " + path, "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/"));
        responses.put("GET athlete/i42", "{\"id\":\"i42\",\"icu_garmin_upload_workouts\":true}");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private IntervalsWriter writer(IntervalsWriter.Mode mode) {
        return new IntervalsWriter(client, mode, journal::add);
    }

    @Test
    void writesOpenKindByKindAndEverythingElseStaysADryRun() {
        assertThat(IntervalsWriter.Write.parse(" workouts, Notes ")).containsExactlyInAnyOrder(
                IntervalsWriter.Write.WORKOUTS, IntervalsWriter.Write.NOTES);
        assertThat(IntervalsWriter.Write.parse("all")).hasSize(IntervalsWriter.Write.values().length);
        assertThat(IntervalsWriter.Write.parse("")).isEmpty();

        IntervalsWriter w = new IntervalsWriter(client, java.util.EnumSet.of(IntervalsWriter.Write.WORKOUTS), journal::add);
        assertThat(w.mode()).isEqualTo(IntervalsWriter.Mode.DRY_RUN);
        assertThat(w.allows(IntervalsWriter.Write.WORKOUTS)).isTrue();
        w.publishNote(DAY, "Green", "train as planned");
        w.comment("a1", "well done");
        w.replaceIntervals("a1", List.of(), List.of());
        assertThat(requests).allMatch(r -> r.startsWith("GET "));
        assertThat(journal).hasSize(3).allMatch(j -> j.startsWith("DRY RUN "));

        responses.put("POST athlete/i42/events", "{\"id\":7,\"external_id\":\"race-radio-x\",\"category\":\"WORKOUT\"}");
        responses.put("GET athlete/i42/events/7", "{\"id\":7,\"external_id\":\"race-radio-x\",\"category\":\"WORKOUT\",\"workout_doc\":" + UNDERSTOOD_DOC + "}");
        assertThat(w.publishWorkout(DAY, SampleWorkouts.bikeVo2())).isPresent();
        assertThat(requests).anyMatch(r -> r.startsWith("POST athlete/i42/events"));
    }

    @Test
    void aDryRunSendsNoWriteAtAllAndDescribesEachOne() {
        IntervalsWriter w = writer(IntervalsWriter.Mode.DRY_RUN);
        assertThat(w.publishWorkout(DAY, SampleWorkouts.bikeVo2())).isEmpty();
        w.publishNote(DAY, "Green", "train as planned");
        w.comment("a1", "well done");
        assertThat(requests).allMatch(r -> r.startsWith("GET "));
        assertThat(journal).hasSize(3).allMatch(j -> j.startsWith("DRY RUN "));
    }

    @Test
    void publishingIsRefusedWhileWorkoutsAreNotPushedToTheWatch() {
        responses.put("GET athlete/i42", "{\"id\":\"i42\",\"icu_garmin_upload_workouts\":false}");
        assertThatThrownBy(() -> writer(IntervalsWriter.Mode.LIVE).publishWorkout(DAY, SampleWorkouts.bikeVo2()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("never reach the watch");
        assertThat(requests).noneMatch(r -> r.startsWith("POST"));
    }

    @Test
    void aWorkoutTheCalendarUnderstoodIsKeptAndCarriesTheOwnershipPrefix() {
        responses.put("POST athlete/i42/events", "{\"id\":7,\"external_id\":\"race-radio-x\"}");
        responses.put("GET athlete/i42/events/7", "{\"id\":7,\"external_id\":\"race-radio-x\",\"workout_doc\":" + UNDERSTOOD_DOC + "}");
        assertThat(writer(IntervalsWriter.Mode.LIVE).publishWorkout(DAY, SampleWorkouts.bikeVo2())).isPresent();
        String post = requests.stream().filter(r -> r.startsWith("POST")).findFirst().orElseThrow();
        assertThat(post).contains("\"external_id\":\"race-radio-").contains("\"category\":\"WORKOUT\"").contains("Main Set 5x");
        assertThat(requests).noneMatch(r -> r.startsWith("DELETE"));
    }

    @Test
    void aWorkoutTheCalendarMisreadIsDeletedAndExplained() {
        responses.put("POST athlete/i42/events", "{\"id\":7,\"external_id\":\"race-radio-x\"}");
        responses.put("GET athlete/i42/events/7", "{\"id\":7,\"external_id\":\"race-radio-x\",\"workout_doc\":null}");
        assertThatThrownBy(() -> writer(IntervalsWriter.Mode.LIVE).publishWorkout(DAY, SampleWorkouts.bikeVo2()))
                .isInstanceOf(IntervalsWriter.WorkoutNotUnderstood.class).hasMessageContaining("did not understand");
        assertThat(requests).contains("DELETE athlete/i42/events/7?others=false");
    }

    @Test
    void entriesNotCreatedByTheCoachAreNeverDeleted() {
        responses.put("GET athlete/i42/events/9", "{\"id\":9,\"external_id\":\"rot-abc\"}");
        assertThatThrownBy(() -> writer(IntervalsWriter.Mode.LIVE).deleteOwnEvent(9))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not created by race-radio");
        responses.put("GET athlete/i42/events/10", "{\"id\":10}");
        assertThatThrownBy(() -> writer(IntervalsWriter.Mode.LIVE).deleteOwnEvent(10)).isInstanceOf(IllegalStateException.class);
        assertThat(requests).noneMatch(r -> r.startsWith("DELETE"));
    }

    @Test
    void intervalsAreWrittenAsLabelledWorkBlocks() {
        writer(IntervalsWriter.Mode.LIVE).replaceIntervals("a1",
                List.of(new io.github.pharaphara.raceradio.core.intervals.Block(900, 1380, 3.6)), List.of("Threshold 1/3"));
        assertThat(requests).anyMatch(r -> r.startsWith("PUT activity/a1/intervals")
                && r.contains("\"start_index\":900") && r.contains("\"label\":\"Threshold 1/3\""));
    }
}
