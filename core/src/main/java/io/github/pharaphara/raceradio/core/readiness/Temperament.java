package io.github.pharaphara.raceradio.core.readiness;

/**
 * How readily the coach eases off. The signals are the same for everyone; only the number of
 * them it takes to change the day differs.
 *
 * <p>{@link #COMMITTED} is the default: green is the normal state, a single isolated reading never
 * changes the day (it is mentioned, not acted on), and it takes at least two concordant signals to
 * ease off. Replayed over a year of real data, it gave about four green days in five, a red on the
 * first morning of each recorded illness, and a red on fewer than one day in twenty.
 */
public enum Temperament {
    CAUTIOUS,
    BALANCED,
    COMMITTED
}
