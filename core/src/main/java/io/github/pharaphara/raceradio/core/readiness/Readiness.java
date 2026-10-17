package io.github.pharaphara.raceradio.core.readiness;

import java.util.List;

/**
 * The morning call and everything that produced it.
 *
 * @param score    0 to 100, a summary for charts; the verdict comes from the rules, not from it
 * @param signals  the reasons to ease off that fired, strongest first
 * @param notes    facts worth saying that did not change the day (an isolated reading, the feel
 *                 overriding the numbers, a resting heart rate unusually low)
 * @param missing  what could not be judged for lack of data
 */
public record Readiness(Verdict verdict, int score, List<Signal> signals, List<String> notes,
                        List<String> missing, Temperament temperament, String model) {
}
