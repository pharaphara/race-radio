package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.model.ActivityInterval;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import io.github.pharaphara.raceradio.icu.model.Athlete;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.icu.model.SportSettings;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A fictional athlete (threshold 250 W, W' 18 kJ, threshold pace 4:30/km) and their sessions. */
final class ReviewFixtures {

    static final LocalDateTime START = LocalDateTime.of(2026, 10, 16, 18, 0);

    private ReviewFixtures() {
    }

    static Samples fiveByThree() {
        List<Samples> parts = new ArrayList<>();
        parts.add(Samples.constant(160, 600));
        for (int i = 0; i < 5; i++) {
            parts.add(Samples.constant(285, 180));
            parts.add(Samples.constant(140, 180));
        }
        parts.add(Samples.constant(130, 300));
        return Samples.concat(parts.toArray(Samples[]::new));
    }

    static ActivitySummary ride(String id) {
        return new ActivitySummary(id, START, "Ride", "Evening ride", 3600, 30_000.0, 200.0, 70, 140.0, 200.0);
    }

    /** Stand-in for Intervals.icu. */
    static class FakeIntervals extends IntervalsClient {
        Map<String, Samples> streams = Map.of();
        List<ActivityInterval> deviceIntervals = List.of();
        List<CalendarEvent> events = List.of();
        List<ActivitySummary> activities = List.of();
        int streamCalls;

        FakeIntervals() {
            super(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/"));
        }

        @Override
        public Athlete athlete() {
            return new Athlete("i42", "Europe/Paris", List.of(
                    new SportSettings(List.of("Ride", "VirtualRide"), 250, 170, null, 18_000),
                    new SportSettings(List.of("Run", "TrailRun"), null, 170, 1000.0 / 270, null),
                    new SportSettings(List.of("Swim", "OpenWaterSwim"), null, 170, 100.0 / 130, null)), true, false);
        }

        @Override
        public ActivityStreams streams(String activityId) {
            streamCalls++;
            return new ActivityStreams(streams);
        }

        @Override
        public List<ActivityInterval> intervals(String activityId) {
            return deviceIntervals;
        }

        @Override
        public List<CalendarEvent> events(LocalDate from, LocalDate to) {
            return events;
        }

        @Override
        public List<ActivitySummary> activities(LocalDate from, LocalDate to) {
            return activities;
        }
    }

    static CalendarEvent plannedRide(String docJson) {
        return new CalendarEvent(1L, START.toLocalDate().atStartOfDay(), "WORKOUT", "Ride", "VO2", "", "race-radio-x", 3600, 70,
                JsonMapper.builder().build().readTree(docJson));
    }
}
