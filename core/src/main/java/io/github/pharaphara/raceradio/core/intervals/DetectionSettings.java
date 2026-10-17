package io.github.pharaphara.raceradio.core.intervals;

/**
 * How finely to look.
 *
 * @param smoothingSeconds  window of the centred rolling mean; long enough to bridge a descent or a
 *                          road junction inside a long block, short enough not to merge two reps
 * @param minWorkSeconds    shortest effort reported; sprints and surges below it are ignored
 * @param endurancePlateau  effort below which nothing counts as work, typically the top of the
 *                          endurance zone (about three quarters of threshold power); NaN when no
 *                          threshold is known, in which case only the session's own contrast is used
 * @param joinSeconds       two pieces closer than this are one block, cut by a junction or a dip
 */
public record DetectionSettings(int smoothingSeconds, int minWorkSeconds, double endurancePlateau, int joinSeconds) {

    public static final int DEFAULT_SMOOTHING = 30;
    public static final int MIN_SMOOTHING = 5;
    public static final int MAX_SMOOTHING = 60;
    /**
     * Without a plan, an effort shorter than this is a hill or a relaunch, not an interval: on an
     * outdoor ride a scale of thirty seconds turned the terrain into a dozen fake reps.
     */
    public static final int UNPLANNED_MIN_WORK = 120;
    /** Without a plan, pieces of one effort split by a bend, a junction or a short descent. */
    public static final int UNPLANNED_JOIN = 60;

    public DetectionSettings {
        if (smoothingSeconds < 1 || minWorkSeconds < 1 || joinSeconds < 0) {
            throw new IllegalArgumentException("windows are at least one second");
        }
    }

    /**
     * Picks the scale from the planned workout when there is one: a sixth of the shortest planned
     * work step, kept between 5 and 60 seconds. The plan chooses how closely to look; it never
     * decides what is found. Without a plan, only sustained efforts count.
     */
    public static DetectionSettings forPlan(Integer shortestPlannedWorkSeconds, double endurancePlateau) {
        if (shortestPlannedWorkSeconds == null) {
            return new DetectionSettings(DEFAULT_SMOOTHING, UNPLANNED_MIN_WORK, endurancePlateau, UNPLANNED_JOIN);
        }
        int smoothing = Math.clamp(shortestPlannedWorkSeconds / 6, MIN_SMOOTHING, MAX_SMOOTHING);
        int minWork = Math.max(30, smoothing);
        return new DetectionSettings(smoothing, minWork, endurancePlateau, smoothing / 2);
    }
}
