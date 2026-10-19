package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.Athlete;
import io.github.pharaphara.raceradio.icu.model.SportSettings;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCommandsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T18:00:00Z"), ZoneId.of("Europe/Paris"));

    @TempDir
    Path dir;

    private Plans plans;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Planner planner;
    private PlanCommands commands;
    private final List<String> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = TestDb.in(dir);
        plans = new Plans(jdbc);
        IntervalsClient icu = new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/")) {
            @Override
            public Athlete athlete() {
                return new Athlete("i42", "Europe/Paris", List.of(
                        new SportSettings(List.of("Ride"), 250, 170, null, 18_000),
                        new SportSettings(List.of("Run"), null, 170, 1000.0 / 270, null),
                        new SportSettings(List.of("Swim"), null, 170, 100.0 / 120, null)), true, false);
            }

            @Override
            public List<WellnessDay> wellness(LocalDate from, LocalDate to) {
                return List.of(new WellnessDay(LocalDate.of(2026, 11, 15), 39.0, 40.0, 1.0, 48, 50.0, 27_000, 80.0, null, null, null, null));
            }
        };
        planner = new Planner(icu, new IntervalsWriter(icu, IntervalsWriter.Mode.DRY_RUN, writes::add), plans, 136, 5, CLOCK);
        commands = new PlanCommands(plans, planner, CLOCK);
    }

    @Test
    void durationsAreReadTheWayTheyAreTyped() {
        assertThat(PlanCommands.minutes("1h")).isEqualTo(60);
        assertThat(PlanCommands.minutes("1h30")).isEqualTo(90);
        assertThat(PlanCommands.minutes("45min")).isEqualTo(45);
        assertThat(PlanCommands.minutes("2 h")).isEqualTo(120);
    }

    @Test
    void availabilityIsKeptPerDay() {
        commands.availability("/dispo lun 1h, mer 45min, jeu 1h, sam 2h, dim 1h30");
        assertThat(plans.availability()).containsEntry(DayOfWeek.MONDAY, 60).containsEntry(DayOfWeek.SUNDAY, 90).hasSize(5);
    }

    @Test
    void aRaceObjectiveIsRecordedAndTheSeasonStartsThatWeek() {
        String reply = commands.objective("/objectif course 2027-05-23 A triathlon M < 2h40");
        assertThat(reply).contains("triathlon M < 2h40").contains("2027-05-23");
        assertThat(plans.objectives()).singleElement().satisfies(o -> assertThat(o.objective().sport()).isEqualTo("Triathlon"));
        assertThat(plans.seasonStart()).contains(LocalDate.of(2026, 11, 9));
        assertThat(commands.objective("/objectif course 2020-01-01 A old")).contains("passée");
    }

    @Test
    void swimmingCanBeSwitchedOffForTheWinter() {
        assertThat(commands.sports("/sports natation off")).contains("vélo").contains("course").doesNotContain("natation");
        assertThat(plans.sports()).doesNotContain("Swim");
    }

    @Test
    void thePlanShowsWhatTheTimeCanBuyAndPointsToTheWeekOnRequest() {
        commands.availability("/dispo lun 1h, mer 45min, jeu 1h, sam 2h, dim 1h30");
        commands.objective("/objectif course 2027-05-23 A triathlon M");
        String plan = commands.plan();
        assertThat(plan).contains("Faisabilité").contains("Un chrono ne se déduit pas").contains("/semaine");
    }

    @Test
    void storedModesFromBeforeTheOnDemandChangeAreReadSafely() {
        commands.mode("/mode demande");
        assertThat(plans.mode()).isEqualTo(Plans.Mode.ON_DEMAND);
        jdbc.update("UPDATE setting SET value = 'PLAN' WHERE key = 'mode'");
        assertThat(plans.mode()).isEqualTo(Plans.Mode.ON_DEMAND);
        jdbc.update("UPDATE setting SET value = 'OFF' WHERE key = 'mode'");
        assertThat(plans.mode()).isEqualTo(Plans.Mode.ANALYSE);
    }

    @Test
    void theSwimOverrideReplacesTheProfilesThreshold() {
        assertThat(planner.thresholds().criticalSwimPace()).isEqualTo(136);
    }

    @Test
    void publishingAWeekInDryRunWritesNothingButDescribesEveryWorkout() {
        commands.availability("/dispo lun 1h, mer 45min, sam 2h");
        var week = planner.nextWeek();
        assertThat(planner.publish(week)).isEmpty();
        assertThat(writes).hasSize(week.sessions().size()).allMatch(w -> w.startsWith("DRY RUN POST athlete/i42/events"));
    }

    @Test
    void theModeCanBeSetSoThatAnotherToolPlans() {
        assertThat(commands.mode("/mode analyser")).contains("Garmin Coach");
        assertThat(plans.mode()).isEqualTo(Plans.Mode.ANALYSE);
    }
}
