package io.github.pharaphara.raceradio.icu;

import com.sun.net.httpserver.HttpServer;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.Athlete;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Offline: a local HTTP server stands in for Intervals.icu with synthetic responses. */
class IntervalsClientTest {

    private static final IntervalsCredentials CREDS = new IntervalsCredentials("i42", "test-key-not-real");

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private volatile String lastAuthorization;
    private IntervalsClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            String key = exchange.getRequestURI().getPath().substring("/api/v1/".length());
            byte[] body = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/");
        client = new IntervalsClient(CREDS, HttpClient.newHttpClient(), base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void authenticatesWithTheLiteralApiKeyUserName() {
        responses.put("athlete/i42", "{\"id\":\"i42\"}");
        client.athlete();
        String expected = "Basic " + Base64.getEncoder().encodeToString("API_KEY:test-key-not-real".getBytes(StandardCharsets.UTF_8));
        assertThat(lastAuthorization).isEqualTo(expected);
    }

    @Test
    void readsThresholdsAndTheGarminPushSetting() {
        responses.put("athlete/i42", """
                {"id":"i42","timezone":"Europe/Paris","icu_garmin_upload_workouts":false,
                 "sportSettings":[{"types":["Ride","VirtualRide"],"ftp":250,"lthr":170,"w_prime":18000},
                                  {"types":["Swim","OpenWaterSwim"],"threshold_pace":0.80}],
                 "someFieldWeDoNotModel":123}""");
        Athlete a = client.athlete();
        assertThat(a.settingsFor("VirtualRide")).get().extracting(s -> s.thresholdPower()).isEqualTo(250);
        assertThat(a.settingsFor("OpenWaterSwim").orElseThrow().thresholdSpeed()).isCloseTo(0.80, within(1e-9));
        assertThat(a.pushesWorkoutsToGarmin()).isFalse();
    }

    @Test
    void wellnessUsesThePublicVocabularyAndComputesForm() {
        responses.put("athlete/i42/wellness", """
                [{"id":"2026-10-16","ctl":40.0,"atl":34.6,"rampRate":2.0,"restingHR":50,"hrv":65.0,"sleepSecs":27000}]""");
        WellnessDay d = client.wellness(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)).getFirst();
        assertThat(d.date()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(d.fitness()).isEqualTo(40.0);
        assertThat(d.form()).isCloseTo(5.4, within(1e-9));
        assertThat(d.sleepSeconds()).isEqualTo(27000);
    }

    @Test
    void tellsItsOwnWorkoutsFromEveryoneElsesAndSpotsMisreadOnes() {
        responses.put("athlete/i42/events", """
                [{"id":1,"category":"WORKOUT","external_id":"race-radio-abc","workout_doc":{"steps":[{"duration":60}]}},
                 {"id":2,"category":"WORKOUT","external_id":"rot-xyz","workout_doc":{"steps":[{"duration":60}]}},
                 {"id":3,"category":"NOTE","name":"A note"},
                 {"id":4,"category":"WORKOUT","external_id":"race-radio-def","workout_doc":null},
                 {"id":5,"category":"WORKOUT","external_id":"race-radio-ghi","workout_doc":{"steps":[],"duration":0}}]""");
        List<CalendarEvent> events = client.events(LocalDate.now(), LocalDate.now());
        assertThat(events).filteredOn(CalendarEvent::ownedByRaceRadio).extracting(CalendarEvent::id).containsExactly(1L, 4L, 5L);
        assertThat(events.get(0).wasUnderstoodAsAWorkout()).isTrue();
        assertThat(events.get(3).wasUnderstoodAsAWorkout()).isFalse();
        // An empty description gives a document that exists but holds nothing: an empty workout on the watch.
        assertThat(events.get(4).wasUnderstoodAsAWorkout()).isFalse();
    }

    @Test
    void readsDetectedIntervals() {
        responses.put("activity/x1/intervals", """
                {"id":"x1","icu_intervals":[
                  {"type":"RECOVERY","start_index":0,"end_index":899,"moving_time":900},
                  {"type":"WORK","start_index":900,"end_index":1379,"moving_time":480,"group_id":"480s@160bpm"}]}""");
        assertThat(client.intervals("x1")).hasSize(2).filteredOn(i -> i.isWork()).singleElement()
                .extracting(i -> i.movingSeconds()).isEqualTo(480);
    }

    @Test
    void errorsAreClassifiedAndCarryPlainWords() {
        statuses.put("athlete/i42", 401);
        responses.put("athlete/i42", "{\"status\":401,\"error\":\"Auth failed\"}");
        assertThatThrownBy(client::athlete)
                .isInstanceOfSatisfying(IntervalsException.class, e -> {
                    assertThat(e.kind()).isEqualTo(IntervalsException.Kind.UNAUTHORIZED);
                    assertThat(e.kind().plainWords()).contains("API key");
                });
    }

    @Test
    void streamsCsvDropsTheByteOrderMarkAndKeepsGapsAsMissing() {
        ActivityStreams s = IntervalsClient.parseStreamsCsv("""
                ﻿time,watts,heartrate,lat,lng
                0,0,110,0.0010,0.0020
                1,300,,0.0011,0.0021
                """);
        assertThat(s.names()).containsExactly("time", "watts", "heartrate", "lat", "lng");
        assertThat(s.get("watts").orElseThrow().at(1)).isEqualTo(300.0);
        assertThat(s.get("heartrate").orElseThrow().at(1)).isNaN();
        assertThat(s.get("lng").orElseThrow().at(0)).isEqualTo(0.0020);
    }

    @Test
    void credentialsNeverPrintTheKey() {
        assertThat(CREDS.toString()).doesNotContain("test-key-not-real").contains("***");
    }
}
