package io.github.pharaphara.raceradio.core.readiness;

/**
 * One reason to ease off, with the evidence behind it stated as plain facts and numbers. The
 * language model receives this sentence and must cite it; it never computes one of its own.
 *
 * @param registry   whether the threshold that fired is an established statistic or a convention
 *                   of practice; the coach does not present a convention as a research finding
 * @param value      the reading that fired, in the kind's natural unit (ms, bpm, hours, form
 *                   points, load, number of low answers), so that any interface can phrase it in
 *                   its own language without parsing the English sentence
 * @param reference  what it was compared with (the athlete's usual value, the sleep score, the
 *                   fitness), or null when there is none
 */
public record Signal(SignalKind kind, Severity severity, String evidence, Registry registry, Double value, Double reference) {

    public enum Registry {
        /** A threshold on a statistic of the athlete's own data. */
        STATISTICAL,
        /** A rule of thumb shared by coaches, useful but not measured or universal. */
        CONVENTION
    }
}
