package io.github.pharaphara.raceradio.icu;

import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import io.github.pharaphara.raceradio.icu.model.Athlete;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only checks against the real API, to catch wire changes the offline tests cannot see.
 * Runs only with {@code -Dlive=true} and credentials in the repository's {@code .env}. Asserts on
 * shapes, never prints values: whose account this is stays out of the build log.
 */
@EnabledIfSystemProperty(named = "live", matches = "true")
class IntervalsLiveTest {

    private static IntervalsClient client;

    @BeforeAll
    static void connect() throws Exception {
        Path env = Path.of("").toAbsolutePath().getParent().resolve(".env");
        client = new IntervalsClient(IntervalsCredentials.from(IntervalsCredentials.readDotEnv(env)));
    }

    @Test
    void profileHasCyclingThresholds() {
        Athlete a = client.athlete();
        assertThat(a.settingsFor("Ride")).get().satisfies(s -> {
            assertThat(s.thresholdPower()).isPositive();
            assertThat(s.wPrime()).isPositive();
        });
    }

    @Test
    void recentWellnessCarriesFitnessAndHeartRateVariability() {
        List<WellnessDay> days = client.wellness(LocalDate.now().minusDays(14), LocalDate.now());
        assertThat(days).isNotEmpty();
        assertThat(days).anySatisfy(d -> {
            assertThat(d.fitness()).isNotNull();
            assertThat(d.hrv()).isNotNull();
        });
    }

    @Test
    void latestActivityStreamsAreReadable() {
        List<ActivitySummary> acts = client.activities(LocalDate.now().minusDays(60), LocalDate.now());
        assertThat(acts).isNotEmpty();
        ActivitySummary latest = acts.getFirst();
        ActivityStreams s = client.streams(latest.id());
        assertThat(s.names()).contains("time");
        assertThat(s.length()).isPositive();
    }
}
