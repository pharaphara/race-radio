package io.github.pharaphara.raceradio.bot.review;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything computed about one session, and nothing else: this is what the model reads and what
 * the decision log keeps. Every number the review may quote is here, already formatted where a
 * reader would want it formatted (paces), so the model never has to compute one.
 *
 * @param enoughData  false when the session has no usable effort signal; the review must then
 *                    say there is not enough to judge, and must not change anything
 */
public record SessionFacts(
        String activityId, String sport, LocalDateTime start, Integer movingSeconds, Double distanceMetres,
        Double elevationGainMetres, Integer load, Double averageHeartRate,
        boolean enoughData, String signal, String unit,
        String structureSource, String structureModel, List<BlockFact> work, List<String> repetitions,
        boolean planned, List<ComparisonFact> againstPlan,
        PowerFacts power, String zoneModel, Map<String, Integer> secondsInZones,
        Double aerobicDecouplingPercent, Double distancePerStrokeMetres) {

    /** One work block: where it sits, how long, and its mean effort as a reader would say it. */
    public record BlockFact(int startSecond, int seconds, String duration, String effort) {
    }

    /** One line of the comparison with the plan; either side may be absent. */
    public record ComparisonFact(String planned, String done, List<String> deviations) {
    }

    public record PowerFacts(long weightedAveragePower, long averagePower, double intensity, long load,
                             double variability, String wPrimeModel, int wPrimeLowestBalancePercent,
                             boolean wPrimeExceeded) {
    }
}
