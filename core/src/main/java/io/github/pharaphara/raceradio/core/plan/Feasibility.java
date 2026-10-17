package io.github.pharaphara.raceradio.core.plan;

import io.github.pharaphara.raceradio.core.load.TrainingLoadModel;

/**
 * What the time available can buy, stated in what can be computed: fitness. A finishing time does
 * not follow from a fitness curve, and the coach says so rather than pretend.
 *
 * <p>Two ceilings apply. Fitness cannot rise faster than the ramp the athlete tolerates, and it
 * cannot settle above the daily load the available hours produce. The lower one is what the
 * race date allows.
 *
 * @param plateau      fitness the weekly hours sustain in the long run
 * @param byDate       fitness reachable by the date with the ramp limit
 * @param limitedBy    which ceiling binds: "time" (hours per week) or "ramp" (rate of increase)
 */
public record Feasibility(double current, double plateau, double byDate, String limitedBy, int weeks) {

    /** Conventional load per hour of mostly easy endurance work. */
    public static final double LOAD_PER_HOUR = 55;

    public static Feasibility of(double currentFitness, double hoursPerWeek, int weeks, double rampPerWeek) {
        double plateau = hoursPerWeek * LOAD_PER_HOUR / 7;
        double ramped = currentFitness + rampPerWeek * Math.max(0, weeks);
        double reachable;
        String limit;
        if (plateau <= currentFitness) {
            reachable = plateau;
            limit = "time";
        } else if (ramped < plateau) {
            reachable = ramped;
            limit = "ramp";
        } else {
            // Approaching a plateau is asymptotic; this is the model's own value after the weeks given.
            double daily = plateau;
            double f = currentFitness;
            for (int d = 0; d < weeks * 7; d++) {
                f = f * Math.exp(-1.0 / TrainingLoadModel.FITNESS_DAYS) + daily * (1 - Math.exp(-1.0 / TrainingLoadModel.FITNESS_DAYS));
            }
            reachable = f;
            limit = "time";
        }
        return new Feasibility(currentFitness, plateau, reachable, limit, weeks);
    }
}
