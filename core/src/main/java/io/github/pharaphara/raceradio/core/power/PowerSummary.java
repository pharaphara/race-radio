package io.github.pharaphara.raceradio.core.power;

/**
 * Headline numbers for a session ridden with a power meter.
 *
 * <p>Vocabulary note. These quantities are widely known under trademarked abbreviations. This
 * project is public, so the code, the API and the messages use plain names instead:
 * weighted average power, intensity, load and variability. The knowledge base names the original
 * framework and its author where the formulas are described.
 *
 * @param seconds               moving seconds that carried a power reading
 * @param averagePower          plain mean, watts
 * @param weightedAveragePower  fourth-power mean of the 30-second rolling average, watts
 * @param intensity             weighted average power divided by threshold power
 * @param load                  physiological cost; one hour at threshold scores 100
 * @param variability           weighted average power divided by average power; 1.0 is perfectly steady
 */
public record PowerSummary(
        int seconds,
        double averagePower,
        double weightedAveragePower,
        double intensity,
        double load,
        double variability) {
}
