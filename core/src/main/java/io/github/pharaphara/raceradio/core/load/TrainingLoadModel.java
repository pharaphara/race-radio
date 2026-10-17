package io.github.pharaphara.raceradio.core.load;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Fitness, fatigue and form from a history of daily load: an impulse-response model in the line
 * of Banister's, as Intervals.icu implements it.
 *
 * <p>Each day, fitness and fatigue move towards that day's load by exponential decay, with time
 * constants of 42 and 7 days. The decay is {@code e^(-1/n)}, not the {@code 1/n} linear shortcut
 * found in many write-ups: replaying an athlete's real loads, the exponential form reproduces the
 * fatigue Intervals.icu reports exactly and its fitness within a tenth of a point, while the linear
 * form drifts by close to three points. Projections made here therefore match what the athlete
 * sees in the app.
 *
 * <p>Form is fitness minus fatigue on the same day. Ramp is fitness today minus fitness seven
 * days earlier, which is also the definition behind Intervals.icu's ramp rate.
 */
public final class TrainingLoadModel {

    public static final int FITNESS_DAYS = 42;
    public static final int FATIGUE_DAYS = 7;
    public static final int RAMP_DAYS = 7;

    private static final double FITNESS_DECAY = Math.exp(-1.0 / FITNESS_DAYS);
    private static final double FATIGUE_DECAY = Math.exp(-1.0 / FATIGUE_DAYS);

    private TrainingLoadModel() {
    }

    /** One day of the model's output. */
    public record Day(LocalDate date, double load, double fitness, double fatigue, double form, double ramp) {
    }

    /**
     * Runs the model day by day from {@code from} to {@code to} inclusive.
     *
     * @param dailyLoad    total load per day; days absent from the map count as rest
     * @param from         first day; the model starts from the given seeds on the day before
     * @param seedFitness  fitness on the day before {@code from}, zero for a cold start
     * @param seedFatigue  fatigue on the day before {@code from}
     */
    public static List<Day> run(Map<LocalDate, Double> dailyLoad, LocalDate from, LocalDate to,
                                double seedFitness, double seedFatigue) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not precede from");
        }
        List<Day> days = new ArrayList<>();
        NavigableMap<LocalDate, Double> fitnessByDay = new TreeMap<>();
        double fitness = seedFitness;
        double fatigue = seedFatigue;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            double load = dailyLoad.getOrDefault(d, 0.0);
            fitness = fitness * FITNESS_DECAY + load * (1 - FITNESS_DECAY);
            fatigue = fatigue * FATIGUE_DECAY + load * (1 - FATIGUE_DECAY);
            fitnessByDay.put(d, fitness);
            Double weekAgo = fitnessByDay.get(d.minusDays(RAMP_DAYS));
            double ramp = weekAgo == null ? Double.NaN : fitness - weekAgo;
            days.add(new Day(d, load, fitness, fatigue, fitness - fatigue, ramp));
        }
        return days;
    }

    /**
     * Steady daily load that, held for one week, raises fitness by {@code rampPerWeek} from its
     * current value. Used by the planner to turn "plus five a week" into a training volume.
     */
    public static double dailyLoadForRamp(double currentFitness, double rampPerWeek) {
        double keep = Math.pow(FITNESS_DECAY, RAMP_DAYS);
        double target = currentFitness + rampPerWeek;
        // fitness after n days of constant load L: F*k^n + L*(1 - k^n)
        return (target - currentFitness * keep) / (1 - keep);
    }
}
