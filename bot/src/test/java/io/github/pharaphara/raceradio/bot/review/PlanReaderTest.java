package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.core.intervals.PlannedStep;
import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.SportSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlanReaderTest {

    private static final Optional<SportSettings> BIKE = Optional.of(new SportSettings(List.of("Ride"), 250, 170, null, 18_000));
    private static final Optional<SportSettings> RUN = Optional.of(new SportSettings(List.of("Run"), null, 170, 1000.0 / 270, null));

    private static EffortSignal signal(String sport, String column, Optional<SportSettings> s) {
        return EffortSignal.of(sport, new ActivityStreams(Map.of(column, Samples.constant(3.5, 100))), s).orElseThrow();
    }

    @Test
    void onlyStepsAboveTheEndurancePlateauAreWork() {
        String doc = """
                {"steps":[{"duration":600,"power":{"start":120,"end":160,"units":"w"}},
                  {"duration":900,"reps":5,"steps":[{"duration":180,"power":{"start":285,"end":300,"units":"w"}},
                                                     {"duration":180,"power":{"value":1,"units":"power_zone"}}]},
                  {"duration":300,"power":{"value":1,"units":"power_zone"}}]}""";
        List<PlannedStep> work = PlanReader.workSteps(JsonMapper.builder().build().readTree(doc), signal("Ride", "watts", BIKE), BIKE);
        assertThat(work).hasSize(5).allSatisfy(p -> {
            assertThat(p.seconds()).isEqualTo(180);
            assertThat(p.targetLow()).isEqualTo(285);
        });
    }

    @Test
    void powerZonesBecomeWattsFromTheThreshold() {
        String doc = "{\"steps\":[{\"duration\":1200,\"power\":{\"value\":4,\"units\":\"power_zone\"}}]}";
        PlannedStep p = PlanReader.workSteps(JsonMapper.builder().build().readTree(doc), signal("Ride", "watts", BIKE), BIKE).getFirst();
        assertThat(p.targetLow()).isCloseTo(0.91 * 250, within(1e-9));
        assertThat(p.targetHigh()).isCloseTo(1.00 * 250, within(1e-9));
    }

    @Test
    void pacesBecomeSpeedsWithTheFastEndHigh() {
        String doc = "{\"steps\":[{\"duration\":480,\"pace\":{\"start\":275,\"end\":280,\"units\":\"secs/km\"}}]}";
        PlannedStep p = PlanReader.workSteps(JsonMapper.builder().build().readTree(doc), signal("Run", "velocity_smooth", RUN), RUN).getFirst();
        assertThat(p.targetHigh()).isCloseTo(1000.0 / 275, within(1e-9));
        assertThat(p.targetLow()).isCloseTo(1000.0 / 280, within(1e-9));
    }

    @Test
    void heartRateZoneWorkIsComparedOnDurationOnly() {
        String doc = """
                {"steps":[{"duration":600,"hr":{"value":2,"units":"hr_zone"}},{"duration":1200,"hr":{"value":4,"units":"hr_zone"}}]}""";
        List<PlannedStep> work = PlanReader.workSteps(JsonMapper.builder().build().readTree(doc), signal("Run", "velocity_smooth", RUN), RUN);
        assertThat(work).singleElement().satisfies(p -> {
            assertThat(p.seconds()).isEqualTo(1200);
            assertThat(p.targetLow()).isNaN();
        });
    }
}
