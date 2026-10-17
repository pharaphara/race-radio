package io.github.pharaphara.raceradio.core.readiness;

/** The morning call. */
public enum Verdict {
    /** Train as planned. */
    GREEN,
    /** Train, but lighter: the coach states exactly what it removes. */
    YELLOW,
    /** Rest or active recovery. */
    RED
}
