package io.github.pharaphara.raceradio.core.readiness;

/** What a readiness signal is about. Each kind is computed from one source, never two. */
public enum SignalKind {
    /** Today's heart rate variability against the athlete's own baseline. */
    HRV_TODAY,
    /** Seven-day trend of heart rate variability against the baseline: accumulated fatigue. */
    HRV_TREND,
    /** Today's resting heart rate against the athlete's own baseline. */
    RESTING_HEART_RATE,
    /** Last night's sleep, by duration and by the device's score. */
    SLEEP,
    /** Fitness minus fatigue. */
    FORM,
    /** Load of the last three days relative to fitness. */
    RECENT_LOAD,
    /** What the athlete reported. */
    FEEL
}
