package io.github.pharaphara.raceradio.core.plan;

/**
 * The thresholds workouts are built from. A missing one means that sport cannot be prescribed in
 * its own unit, and the planner leaves it out rather than guess.
 *
 * @param thresholdPower    watts
 * @param thresholdPace     seconds per kilometre
 * @param criticalSwimPace  seconds per 100 m
 */
public record Thresholds(Integer thresholdPower, Integer thresholdPace, Integer criticalSwimPace) {
}
