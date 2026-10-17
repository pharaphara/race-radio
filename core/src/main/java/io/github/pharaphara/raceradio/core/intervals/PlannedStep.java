package io.github.pharaphara.raceradio.core.intervals;

/**
 * One work step of the planned workout, in the same unit as the signal it will be compared with.
 *
 * @param targetLow   lower bound of the target, or NaN when the plan gave none
 * @param targetHigh  upper bound of the target, or NaN
 */
public record PlannedStep(int seconds, double targetLow, double targetHigh) {
}
