package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.plan.StrengthSessions;
import io.github.pharaphara.raceradio.bot.strength.Exercises;
import io.github.pharaphara.raceradio.bot.strength.StrengthWorkout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.button;
import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

/** Strength sessions from what the athlete owns, timed on the watch, the exercises in the note. */
class StrengthSessionTest {

    @TempDir
    Path dir;

    private AthleteFixture f;

    private static String session(String exercise) {
        return """
                {"why": "Renfo pour le vélo.", "note": "Contrôle le geste, effort 6 sur 10.",
                 "workout": {"name": "Renfo vélo · 20 min", "blocks": [
                   {"title": "Échauffement", "rounds": 1, "items": [{"exercise": "mobilite_hanches", "seconds": 300, "reps": null}]},
                   {"title": "Circuit", "rounds": 3, "items": [
                     {"exercise": "%s", "seconds": 40, "reps": 12}, {"exercise": null, "seconds": 20, "reps": null},
                     {"exercise": "gainage_ventral", "seconds": 40, "reps": null}, {"exercise": null, "seconds": 60, "reps": null}]},
                   {"title": "Retour au calme", "rounds": 1, "items": [{"exercise": "etirements", "seconds": 240, "reps": null}]}]}}"""
                .formatted(exercise);
    }

    @BeforeEach
    void start() throws IOException {
        f = new AthleteFixture(dir);
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    void equipmentIsReadFromPlainWords() {
        assertThat(Exercises.equipmentIn("Haltères 2 × 10 kg, kettlebell 16 kg, des élastiques et une barre de traction"))
                .containsExactlyInAnyOrder("halteres", "kettlebell", "elastique", "barre_traction");
        assertThat(Exercises.equipmentIn("rien, juste un tapis")).isEmpty();
        assertThat(StrengthSessions.asked("demain renfo 30 min")).isTrue();
        assertThat(StrengthSessions.asked("jeudi gainage et PPG")).isTrue();
        assertThat(StrengthSessions.asked("une séance de force à vélo")).isFalse();
    }

    @Test
    void aSessionIsTimedStepsTheCalendarReadsAndRefusesWhatTheAthleteCannotDo() {
        StrengthWorkout w = StrengthWorkout.of(JsonMapper.builder().build().readTree(session("swing_kettlebell")).path("workout"));
        assertThat(w.seconds()).isEqualTo(300 + 3 * 160 + 240);
        assertThat(w.steps()).isEqualTo("Échauffement\n- 5m\n\nCircuit 3x\n- 40s\n- 20s\n- 40s\n- 1m\n\nRetour au calme\n- 4m");
        assertThat(w.lines(Exercises.catalogue()).get(1)).startsWith("Circuit, 3 tours : Swing kettlebell 40 s (≈ 12) · récup 20 s");
        assertThat(w.problems(Exercises.catalogue(), Set.of())).anySatisfy(p -> assertThat(p).contains("needs kettlebell"));
        assertThat(w.problems(Exercises.catalogue(), Set.of("kettlebell"))).isEmpty();
        StrengthWorkout unknown = StrengthWorkout.of(JsonMapper.builder().build().readTree(session("planche_dragon")).path("workout"));
        assertThat(unknown.problems(Exercises.catalogue(), Set.of("kettlebell"))).anySatisfy(p -> assertThat(p).contains("unknown exercise"));
    }

    @Test
    void anAthleteWithBandsOnlyGetsASessionWithoutTheKettlebell() {
        f.dispatcher.handleNow(message(7, "/materiel des élastiques"));
        assertThat(f.to(7).getLast()).contains("élastiques");

        f.answers.add(session("swing_kettlebell"));
        f.answers.add(session("monster_walk"));
        f.dispatcher.handleNow(message(7, "/seance demain renfo 20 min"));
        assertThat(f.prompts).hasSize(2);
        assertThat(f.prompts.getFirst()).contains("monster_walk").doesNotContain("swing_kettlebell:");
        assertThat(f.prompts.get(1)).contains("swing_kettlebell needs kettlebell");
        String preview = f.to(7).getLast();
        assertThat(preview).contains("Marche latérale élastique").contains("Publier").contains("Ta montre minutera");

        String id = preview.replaceAll("(?s).*spub:(\\d+).*", "$1");
        f.dispatcher.handleNow(button(7, "spub:" + id));
        // The service runs dry in the fixture: nothing reaches the calendar, and the athlete is told.
        assertThat(f.to(7).getLast()).contains("Mode sec");
    }
}
