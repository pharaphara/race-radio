package io.github.pharaphara.raceradio.icu.workout;

import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;

import java.util.List;

/** Workouts shared by the tests of this module. */
public final class SampleWorkouts {

    private SampleWorkouts() {
    }

    /** Warm-up with cadence and cue, five by three minutes, and an odd-length cool-down. */
    public static Workout bikeVo2() {
        return new Workout("Ride", "VO2", List.of(
                new Section("Warmup", 1, List.of(new Step(new Length.Time(600), new Target.Power(120, 160), 90, "easy spin"))),
                new Section("Main Set", 5, List.of(
                        Step.of(new Length.Time(180), new Target.Power(285, 300)),
                        Step.of(new Length.Time(180), new Target.PowerZone(1)))),
                new Section("Cooldown", 1, List.of(Step.of(new Length.Time(330), new Target.PowerZone(1))))));
    }
}
