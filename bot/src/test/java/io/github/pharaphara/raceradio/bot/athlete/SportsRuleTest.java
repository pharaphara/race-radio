package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.plan.SessionCreator;
import io.github.pharaphara.raceradio.bot.plan.Sports;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.plan.WeekCheck;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Workout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The sports an athlete practises steer what the coach chooses; what they ask for is done. */
class SportsRuleTest {

    @TempDir
    Path dir;

    private AthleteFixture f;

    private static String workout(String sport) {
        return """
                {"why": "Une séance facile.", "note": "Reste à l'aise.", "workout": {"sport": "%s", "name": "Facile · 45 min", "sections": [
                  {"title": "Main Set", "repeats": 1, "steps": [{"seconds": 2700, "metres": null, "kind": "HEART_RATE_ZONE", "low": 2,
                   "high": null, "cadence": null, "cue": "souple"}]}]}}""".formatted(sport);
    }

    @BeforeEach
    void start() throws IOException {
        f = new AthleteFixture(dir);
        new Plans(f.friend).sports(Set.of("Ride"));
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    void whenTheSportIsLeftOpenTheCoachStaysInTheAthletesSports() {
        SessionCreator sessions = f.services(f.friend).sessions();
        f.answers.add(workout("Run"));
        f.answers.add(workout("Ride"));
        SessionCreator.Prepared p = sessions.prepare("/seance demain facile");
        assertThat(p.pending()).isNotNull();
        assertThat(p.pending().items().getFirst().workout().sport()).isEqualTo("Ride");
        assertThat(f.prompts).hasSize(2);
        assertThat(f.prompts.getFirst()).contains("Sports the athlete practises: Ride");
        assertThat(f.prompts.get(1)).contains("practises only Ride");
    }

    @Test
    void aSportTheAthleteNamesIsDoneWithoutDiscussion() {
        f.answers.add("""
                {"why": "Natation demandée.", "note": "Allonge la nage.", "workout": {"sport": "Swim", "name": "Natation · 1500 m", "sections": [
                  {"title": "Main Set", "repeats": 1, "steps": [{"seconds": null, "metres": 1500, "kind": "PACE", "low": 140,
                   "high": null, "cadence": null, "cue": "allonge"}]}]}}""");
        SessionCreator.Prepared p = f.services(f.friend).sessions().prepare("/seance demain natation 30 min");
        assertThat(p.pending()).as(p.message()).isNotNull();
        assertThat(p.pending().items().getFirst().workout().sport()).isEqualTo("Swim");
        assertThat(f.prompts).hasSize(1);
    }

    @Test
    void aWeekWithASportTheAthleteDoesNotPractiseBreaksARule() {
        Thresholds t = new Thresholds(250, 300, 120);
        Workout run = JsonMapper.builder().build().treeToValue(JsonMapper.builder().build().readTree(workout("Run")).path("workout"), Draft.class).build(t);
        LocalDate monday = LocalDate.of(2026, 10, 26);
        assertThat(WeekCheck.problems(monday, 7, List.of(new WeekCheck.Planned(monday, run)), t, Set.of("Ride")))
                .anySatisfy(p -> assertThat(p).contains("ne pratique pas"));
        assertThat(WeekCheck.problems(monday, 7, List.of(new WeekCheck.Planned(monday, run)), t, Set.of("Ride", "Run"))).isEmpty();
        assertThat(WeekCheck.family("VirtualRide")).isEqualTo("Ride");
        assertThat(WeekCheck.family("TrailRun")).isEqualTo("Run");
    }

    @Test
    void theWordsOfARequestNameItsSport() {
        assertThat(Sports.named("demain footing facile")).containsExactly("Run");
        assertThat(Sports.named("une heure de home-trainer")).containsExactly("Ride");
        assertThat(Sports.named("piscine 2000 m")).containsExactly("Swim");
        assertThat(Sports.named("une marche rapide")).isEmpty();
        assertThat(Sports.named("ma course de dimanche")).isEmpty();
        assertThat(Sports.examples(Set.of("Ride"))).allSatisfy(e -> assertThat(e).contains("vélo"));
        assertThat(Sports.examples(Set.of("Run")).getFirst()).contains("footing");
    }
}
