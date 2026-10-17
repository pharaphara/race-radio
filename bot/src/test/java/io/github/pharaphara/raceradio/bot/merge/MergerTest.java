package io.github.pharaphara.raceradio.bot.merge;

import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MergerTest {

    private final Map<String, ActivitySummary> activities = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();

    private Merger merger() {
        IntervalsClient icu = new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/")) {
            @Override
            public ActivitySummary activity(String id) {
                return activities.get(id);
            }

            @Override
            public ActivityStreams streams(String id) {
                Map<String, Samples> m = new LinkedHashMap<>();
                m.put("time", Samples.of(0, 1));
                m.put("distance", Samples.of(0, 5));
                m.put("heartrate", Samples.of(120, Double.NaN));
                m.put("lat", Samples.of(0.001, 0.002));
                m.put("lng", Samples.of(0.003, 0.004));
                return new ActivityStreams(m);
            }
        };
        return new Merger(icu, new IntervalsWriter(icu, IntervalsWriter.Mode.DRY_RUN, journal::add));
    }

    private void activity(String id, String type, LocalDateTime start, int seconds) {
        activities.put(id, new ActivitySummary(id, start, type, "Sortie " + id, seconds, 10_000.0, null, 40, null, null));
    }

    @Test
    void theEarlierActivityReceivesBothAndTheLaterIsDeleted() {
        activity("i2", "Ride", LocalDateTime.of(2026, 10, 17, 10, 40), 2400);
        activity("i1", "Ride", LocalDateTime.of(2026, 10, 17, 9, 0), 5400);
        assertThat(merger().preview("i2", "i1").first().id()).isEqualTo("i1");
        merger().merge("i2", "i1");
        assertThat(journal.get(0)).startsWith("DRY RUN PUT activity/i1/streams")
                .contains("\"type\":\"latlng\"").contains("\"data2\":[0.003,0.004,0.003,0.004]").contains("null");
        assertThat(journal.get(1)).isEqualTo("DRY RUN DELETE activity/i2");
    }

    @Test
    void twoSportsAreNeverMerged() {
        activity("i1", "Ride", LocalDateTime.of(2026, 10, 17, 9, 0), 3600);
        activity("i2", "Run", LocalDateTime.of(2026, 10, 17, 10, 5), 900);
        Merger.Preview p = merger().preview("i1", "i2");
        assertThat(p.possible()).isFalse();
        assertThat(merger().merge("i1", "i2")).contains("Refusé");
        assertThat(journal).isEmpty();
    }

    @Test
    void twoSessionsHoursApartAreTwoSessions() {
        activity("i1", "Run", LocalDateTime.of(2026, 10, 17, 7, 0), 2400);
        activity("i2", "Run", LocalDateTime.of(2026, 10, 17, 18, 0), 2400);
        assertThat(merger().preview("i1", "i2").text()).contains("deux séances");
    }

    @Test
    void differentDaysAreRefused() {
        activity("i1", "Run", LocalDateTime.of(2026, 10, 17, 23, 30), 1200);
        activity("i2", "Run", LocalDateTime.of(2026, 10, 18, 0, 10), 1200);
        assertThat(merger().preview("i1", "i2").possible()).isFalse();
    }
}
