package io.github.pharaphara.raceradio.core.workout;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned workout as the coach intends it. It is rendered to text for the calendar, and after
 * publication the calendar's own reading of that text is compared with {@link #flatten()}: a
 * workout only counts as published when both agree, step for step.
 *
 * @param sport  an Intervals.icu activity type: Ride, VirtualRide, Run, TrailRun, Swim, ...
 */
public record Workout(String sport, String name, List<Section> sections) {

    public Workout {
        if (sections == null || sections.isEmpty()) {
            throw new IllegalArgumentException("a workout has at least one section");
        }
        sections = List.copyOf(sections);
    }

    /** Every step in the order it is done, repeats expanded. */
    public List<Step> flatten() {
        List<Step> out = new ArrayList<>();
        for (Section s : sections) {
            for (int r = 0; r < s.repeats(); r++) {
                out.addAll(s.steps());
            }
        }
        return out;
    }
}
