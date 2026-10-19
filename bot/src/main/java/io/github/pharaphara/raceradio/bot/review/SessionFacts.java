package io.github.pharaphara.raceradio.bot.review;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything computed about one session, and nothing else: this is what the model reads and what
 * the decision log keeps. Every number the review may quote is here, already formatted where a
 * reader would want it formatted (paces), so the model never has to compute one.
 *
 * @param enoughData      false when the session has no usable effort signal; the review must then
 *                        say there is not enough to judge, and must not change anything
 * @param threshold       the athlete's declared threshold in the signal's unit (watts, or metres per
 *                        second for a pace), or null when the profile has none
 * @param thresholdLabel  the same as a reader says it: "FTP 261 W", "seuil 4:20/km", "CSS 2:00/100 m"
 */
public record SessionFacts(
        String activityId, String sport, LocalDateTime start, Integer movingSeconds, Double distanceMetres,
        Double elevationGainMetres, Integer load, Double averageHeartRate,
        boolean enoughData, String signal, String unit,
        String structureSource, String structureModel, List<BlockFact> work, List<String> repetitions,
        boolean planned, List<ComparisonFact> againstPlan,
        PowerFacts power, String zoneModel, Map<String, Integer> secondsInZones,
        Double aerobicDecouplingPercent, Double distancePerStrokeMetres, Double threshold, String thresholdLabel,
        Context context) {

    /**
     * What surrounds the numbers: how it felt, the conditions, the best efforts, the morning before
     * and what comes next. Every field may be null or empty when unknown.
     *
     * @param rpe            effort out of 10 entered on the watch (Garmin), never asked by the coach
     * @param feel           the athlete's feel as entered on the watch, worded
     * @param movingAverage  mean effort over the seconds spent moving, pauses left out
     * @param cadence        steps per minute on foot (both legs), revolutions per minute on the bike
     * @param morning        that morning's heart-rate variability, resting heart rate and sleep against the week before
     * @param nextPlanned    what is on the calendar in the next three days
     * @param segments       notable passages over regular segments: a record, a top-three time, the gap to the record
     */
    public record Context(Integer rpe, String feel, Double temperatureCelsius, String movingAverage, Integer cadence,
                          String cadenceUnit, List<PeakFact> peaks, String morning, List<String> nextPlanned, List<String> segments) {

        public static final Context NONE = new Context(null, null, null, null, null, null, List.of(), null, List.of(), List.of());
    }

    /** The best mean effort over a window of the session, and the heart rate it cost. */
    public record PeakFact(String window, String effort, Integer heartRate) {
    }

    /**
     * One work block: where it sits, how long, and its mean effort as a reader would say it.
     *
     * @param heartRate    mean heart rate over the block, null without a heart-rate stream
     * @param climbMetres  metres climbed during the block, null without altitude: a slower rep that
     *                     climbed more is explained by the terrain, not by fatigue
     */
    public record BlockFact(int startSecond, int seconds, String duration, String effort, Integer heartRate, Integer climbMetres) {
    }

    /** One line of the comparison with the plan; either side may be absent. */
    public record ComparisonFact(String planned, String done, List<String> deviations) {
    }

    public record PowerFacts(long weightedAveragePower, long averagePower, double intensity, long load,
                             double variability, String wPrimeModel, int wPrimeLowestBalancePercent,
                             boolean wPrimeExceeded) {
    }
}
