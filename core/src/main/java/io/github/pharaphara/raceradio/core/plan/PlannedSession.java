package io.github.pharaphara.raceradio.core.plan;

import io.github.pharaphara.raceradio.core.workout.Workout;

import java.time.LocalDate;

/** One workout on one day, and what it is for in the week. */
public record PlannedSession(LocalDate day, Workout workout, Role role) {

    public enum Role {
        EASY, LONG, HARD, BRICK, TECHNIQUE, RECOVERY
    }

    public boolean hard() {
        return role == Role.HARD || role == Role.BRICK;
    }

    /** Duration in minutes; a distance step is timed at the slow end of its pace target. */
    public int minutes() {
        int seconds = 0;
        for (var s : workout.flatten()) {
            if (s.length() instanceof io.github.pharaphara.raceradio.core.workout.Length.Time t) {
                seconds += t.seconds();
            } else if (s.length() instanceof io.github.pharaphara.raceradio.core.workout.Length.Distance d) {
                if (s.target() instanceof io.github.pharaphara.raceradio.core.workout.Target.SwimPace p) {
                    seconds += d.metres() * p.slow() / 100;
                } else if (s.target() instanceof io.github.pharaphara.raceradio.core.workout.Target.RunPace p) {
                    seconds += d.metres() * p.slow() / 1000;
                }
            }
        }
        return seconds / 60;
    }
}
