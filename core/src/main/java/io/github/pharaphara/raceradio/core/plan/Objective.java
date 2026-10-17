package io.github.pharaphara.raceradio.core.plan;

import java.time.LocalDate;

/**
 * What the training is for. A race has a date; a volume or fitness objective runs until
 * withdrawn. Several can coexist: the plan builds towards the nearest A race, treats B and C races
 * as hard days with a lighter week around them, and falls back to maintenance without any race.
 *
 * @param target  free text kept as the athlete wrote it ("< 2h40", "6 h par semaine", "40 de forme")
 */
public record Objective(Kind kind, String sport, LocalDate date, Priority priority, String target) {

    public enum Kind {
        /** A dated event. */
        RACE,
        /** A number of hours a week to hold. */
        VOLUME,
        /** Keep fitness where it is, or reach a level, without a race. */
        FITNESS
    }

    public enum Priority {
        A, B, C
    }

    public Objective {
        if (kind == Kind.RACE && date == null) {
            throw new IllegalArgumentException("a race has a date");
        }
    }
}
