package io.github.pharaphara.raceradio.core.workout;

import java.util.Objects;

/**
 * One step of a workout.
 *
 * @param cadence  target cadence in rpm, or null
 * @param cue      a few words shown on the watch, such as "easy spin"; may be empty
 */
public record Step(Length length, Target target, Integer cadence, String cue) {

    public Step {
        Objects.requireNonNull(length, "length");
        Objects.requireNonNull(target, "target");
        cue = cue == null ? "" : cue.strip();
    }

    public static Step of(Length length, Target target) {
        return new Step(length, target, null, "");
    }
}
