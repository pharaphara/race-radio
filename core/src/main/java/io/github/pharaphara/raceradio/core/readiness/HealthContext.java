package io.github.pharaphara.raceradio.core.readiness;

/**
 * Illness or injury the athlete marked on the calendar. Context, not a physiological signal: it
 * only ever makes the day more careful, never less, and never forces rest by itself. The marking
 * is not the illness either: a marker that ended says nothing about whether the athlete recovered,
 * so the coach asks. Adapted from the open Section 11 protocol (v11.61, health context).
 *
 * @param active          a marker spans today
 * @param daysSinceEnd    days since the most recent marker ended, or null when none ended recently
 * @param kind            ILLNESS or INJURY
 */
public record HealthContext(boolean active, Integer daysSinceEnd, String kind) {

    public static final HealthContext NONE = new HealthContext(false, null, null);
    /** A marker that ended within this many days is still worth asking about. */
    public static final int RECENT_DAYS = 7;

    public boolean recent() {
        return !active && daysSinceEnd != null && daysSinceEnd <= RECENT_DAYS;
    }
}
