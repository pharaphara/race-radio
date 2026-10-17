package io.github.pharaphara.raceradio.core.workout;

import java.util.List;

/** A titled group of steps, done {@code repeats} times. */
public record Section(String title, int repeats, List<Step> steps) {

    public Section {
        if (repeats < 1) {
            throw new IllegalArgumentException("a section is done at least once");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("a section has at least one step");
        }
        steps = List.copyOf(steps);
        title = title == null ? "" : title.strip();
    }
}
