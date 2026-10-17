package io.github.pharaphara.raceradio.core.intervals;

/** How a done block differs from the planned step it was matched to. */
public enum Deviation {
    AS_PLANNED,
    LONGER,
    SHORTER,
    HARDER,
    EASIER,
    /** A planned step with no matching block. */
    MISSING,
    /** A block with no planned step: a surge, a climb, an extra rep. */
    EXTRA
}
