package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Workout;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionCreatorTest {

    /** A Saturday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final Thresholds T = new Thresholds(250, 270, 130);

    @Test
    void readsTheDayTheWayItIsSaid() {
        assertThat(SessionCreator.day("demain", TODAY)).isEqualTo(TODAY.plusDays(1));
        assertThat(SessionCreator.day("aujourd'hui", TODAY)).isEqualTo(TODAY);
        assertThat(SessionCreator.day("samedi", TODAY)).isEqualTo(TODAY);
        assertThat(SessionCreator.day("mer", TODAY)).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(SessionCreator.day("3/11", TODAY)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(SessionCreator.day("2/1", TODAY)).isEqualTo(LocalDate.of(2027, 1, 2));
    }

    @Test
    void aModelAnswerBecomesAWorkoutWithTheAthletesNumbers() {
        String answer = """
                ```json
                {"sport": "Ride", "name": "Seuil", "sections": [
                  {"title": "Warmup", "repeats": 1, "steps": [{"seconds": 600, "metres": null, "kind": "THRESHOLD_POWER_PERCENT", "low": 55, "high": 70, "cadence": null, "cue": "tranquille"}]},
                  {"title": "Main Set", "repeats": 3, "steps": [
                    {"seconds": 600, "metres": null, "kind": "WATTS", "low": 280, "high": null, "cadence": 90, "cue": "régulier"},
                    {"seconds": 300, "metres": null, "kind": "POWER_ZONE", "low": 1, "high": null, "cadence": null, "cue": ""}]},
                  {"title": "Cooldown", "repeats": 1, "steps": [{"seconds": 300, "metres": null, "kind": "THRESHOLD_POWER_PERCENT", "low": 50, "high": null, "cadence": null, "cue": "3 x souple"}]}]}
                ```""";
        Draft d = JsonMapper.builder().build().readValue(SessionCreator.json(answer), Draft.class);
        Workout w = SessionCreator.buildable(d.build(T));
        // The cue with a digit would be read as an instruction by the calendar: cues are dropped.
        assertThat(w.flatten()).allMatch(s -> s.cue().isEmpty());
        String preview = SessionCreator.preview(TODAY.plusDays(1), w, T, "Trois blocs au seuil, tu es frais.",
                "Tiens la deuxième moitié de chaque bloc. RPE 7 sur 10.", "");
        assertThat(preview).contains("« Seuil » · vélo · 1 h 00 · dimanche 18 octobre")
                .contains("Trois blocs au seuil, tu es frais.")
                .contains("Corps de séance · 3 × (10 min à 280 W, 90 rpm puis 5 min à zone 1)")
                .contains("Note d'exécution : Tiens la deuxième moitié")
                .contains("Ça te va ?");
    }

    @Test
    void distanceStepsShowTheTimeTheyWillTakeAtTheirPace() {
        Workout w = new Draft("Run", "VO2", List.of(new Draft.DraftSection("Main Set", 5, List.of(
                new Draft.DraftStep(null, 1000, Draft.Kind.PACE, 250, 260.0, null, "relâché"),
                new Draft.DraftStep(120, null, Draft.Kind.HEART_RATE_ZONE, 1, null, null, ""))))).build(T);
        assertThat(WorkoutFormat.lines(w, T)).containsExactly(
                "Corps de séance · 5 × (1 km ≈ 4 min 15 à 4:10-4:20/km « relâché » puis 2 min à FC zone 1)");
    }

    @Test
    void aWeekIsReadDaySortedFromTheModelsAnswer() {
        String answer = """
                {"why": "Une semaine de reprise.", "days": [
                  {"day": "2026-10-21", "note": "Facile.", "workout": {"sport": "Run", "name": "Course facile · 40 min", "sections": [
                     {"title": "Main Set", "repeats": 1, "steps": [{"seconds": 2400, "metres": null, "kind": "THRESHOLD_PACE_OFFSET", "low": 50, "high": 70, "cadence": null, "cue": "souple"}]}]}},
                  {"day": "2026-10-19", "note": "- Seuil.\\n3x", "workout": {"sport": "Ride", "name": "Vélo seuil · 3 × 10 min", "sections": [
                     {"title": "Main Set", "repeats": 3, "steps": [{"seconds": 600, "metres": null, "kind": "THRESHOLD_POWER_PERCENT", "low": 95, "high": 100, "cadence": null, "cue": "régulier"},
                                                                  {"seconds": 300, "metres": null, "kind": "POWER_ZONE", "low": 1, "high": null, "cadence": null, "cue": ""}]}]}}]}""";
        var root = JsonMapper.builder().build().readTree(SessionCreator.json(answer));
        List<SessionCreator.Item> items = SessionCreator.weekItems(root, T);
        assertThat(items).extracting(SessionCreator.Item::day).containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21));
        assertThat(items.getFirst().note()).isEqualTo("Seuil. 3 fois");
        String week = SessionCreator.weekPreview(LocalDate.of(2026, 10, 19), items, T, "Une semaine de reprise.");
        assertThat(week).contains("lun. 19 · Vélo seuil · 3 × 10 min · 45 min · dure").contains("mar. 20 · repos")
                .contains("mer. 21 · Course facile · 40 min · 40 min").contains("1 séance dure");
    }

    @Test
    void theModelsAnswerCarriesItsReasonBesideTheWorkout() {
        String answer = """
                {"why": "Footing facile, tu sors d'une grosse sortie samedi.",
                 "workout": {"sport": "Run", "name": "Footing", "sections": [
                   {"title": "Main Set", "repeats": 1, "steps": [{"seconds": 2700, "metres": null, "kind": "THRESHOLD_PACE_OFFSET", "low": 50, "high": 70, "cadence": null, "cue": ""}]}]}}""";
        var root = JsonMapper.builder().build().readTree(SessionCreator.json(answer));
        Draft d = JsonMapper.builder().build().treeToValue(root.path("workout"), Draft.class);
        assertThat(d.build(T).flatten().getFirst().target()).isEqualTo(new io.github.pharaphara.raceradio.core.workout.Target.RunPace(320, 340));
        assertThat(root.path("why").asString()).startsWith("Footing facile");
    }

    @Test
    void anAnswerWithoutAWorkoutIsRefused() {
        assertThatThrownBy(() -> SessionCreator.json("Je ne sais pas.")).isInstanceOf(IllegalArgumentException.class);
    }
}
