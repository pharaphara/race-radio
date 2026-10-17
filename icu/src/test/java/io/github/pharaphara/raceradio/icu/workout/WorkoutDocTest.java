package io.github.pharaphara.raceradio.icu.workout;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WorkoutDocTest {

    private static JsonNode json(String s) {
        return JsonMapper.builder().build().readTree(s);
    }

    /** The shape Intervals.icu returns for the workout of {@link SampleWorkouts#bikeVo2()}. */
    static final String UNDERSTOOD = """
            {"duration":3030,"steps":[
              {"duration":600,"warmup":true,"power":{"start":120,"end":160,"units":"w"},"cadence":{"value":90,"units":"rpm"}},
              {"duration":1800,"reps":5,"text":"Main Set 5x","steps":[
                 {"duration":180,"power":{"start":285,"end":300,"units":"w"}},
                 {"duration":180,"power":{"value":1,"units":"power_zone"}}]},
              {"duration":330,"cooldown":true,"power":{"value":1,"units":"power_zone"}}]}""";

    @Test
    void repeatsAreExpandedInOrder() {
        var steps = WorkoutDoc.flatten(json(UNDERSTOOD));
        assertThat(steps).hasSize(12);
        assertThat(steps.get(1).units()).isEqualTo("w");
        assertThat(steps.get(2).units()).isEqualTo("power_zone");
    }

    @Test
    void anUnderstoodWorkoutAgreesWithTheIntent() {
        assertThat(WorkoutDoc.disagreements(SampleWorkouts.bikeVo2(), json(UNDERSTOOD))).isEmpty();
    }

    @Test
    void aMisreadDurationIsReported() {
        String misread = UNDERSTOOD.replace("\"duration\":330", "\"duration\":30");
        assertThat(WorkoutDoc.disagreements(SampleWorkouts.bikeVo2(), json(misread)))
                .singleElement().asString().contains("step 12").contains("330");
    }

    @Test
    void aWorkoutTheCalendarDidNotUnderstandAtAllIsReported() {
        assertThat(WorkoutDoc.disagreements(SampleWorkouts.bikeVo2(), json("null"))).singleElement()
                .asString().contains("did not understand");
    }

    @Test
    void aWrongTargetKindIsReported() {
        String hr = UNDERSTOOD.replace("{\"start\":285,\"end\":300,\"units\":\"w\"}", "{\"value\":4,\"units\":\"hr_zone\"}");
        assertThat(WorkoutDoc.disagreements(SampleWorkouts.bikeVo2(), json(hr))).isNotEmpty()
                .first().asString().contains("expected a w target");
    }
}
