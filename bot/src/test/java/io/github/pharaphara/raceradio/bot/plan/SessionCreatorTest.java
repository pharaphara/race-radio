package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Workout;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

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
        String preview = SessionCreator.preview(TODAY.plusDays(1), w, T, "");
        assertThat(preview).contains("« Seuil » · vélo · 1h00 · dimanche 18 octobre")
                .contains("Main Set 3x").contains("280w").contains("Je la publie ?");
    }

    @Test
    void anAnswerWithoutAWorkoutIsRefused() {
        assertThatThrownBy(() -> SessionCreator.json("Je ne sais pas.")).isInstanceOf(IllegalArgumentException.class);
    }
}
