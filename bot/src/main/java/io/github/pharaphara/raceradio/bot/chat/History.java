package io.github.pharaphara.raceradio.bot.chat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What the conversation may know of the past, read from the local archive: no call to Intervals.icu.
 * Recent days go into every message; anything older is looked up by the program when the model asks
 * for it, so the figures the athlete reads are always the program's, never the model's.
 */
public interface History {

    /** One line per morning: heart-rate variability, resting heart rate, sleep, weight. */
    List<String> recentDays(int days);

    /** One line per session. */
    List<String> recentSessions(int days);

    /** Everything archived about one day, worded for the athlete, or empty when nothing is. */
    Optional<String> day(LocalDate day);

    /** Sends a metric's trend to the athlete. False when the metric is unknown. */
    boolean trend(String metricKey, int months);

    /** The metrics {@link #trend} knows, one "key: label" line each. */
    List<String> metrics();

    History NONE = new History() {
        @Override
        public List<String> recentDays(int days) {
            return List.of();
        }

        @Override
        public List<String> recentSessions(int days) {
            return List.of();
        }

        @Override
        public Optional<String> day(LocalDate day) {
            return Optional.empty();
        }

        @Override
        public boolean trend(String metricKey, int months) {
            return false;
        }

        @Override
        public List<String> metrics() {
            return List.of();
        }
    };
}
