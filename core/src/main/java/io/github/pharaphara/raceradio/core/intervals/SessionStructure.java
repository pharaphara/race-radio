package io.github.pharaphara.raceradio.core.intervals;

import io.github.pharaphara.raceradio.core.series.Samples;

import java.util.List;

/**
 * Decides where the session's structure comes from, and says so.
 *
 * <p>When the session was done with the structured workout on the watch or the bike computer, the
 * device lapped each step, and those laps are the truth: on a hilly run, for instance, pace alone
 * cannot tell an uphill threshold rep from a fast flat warm-up, but the lap can. The tell-tale sign
 * is that laps cut by a workout land exactly on the planned durations, so the device blocks are
 * trusted when at least half the planned steps match one to within five percent. Steps that do not
 * match are then real deviations, such as a last rep held longer on purpose, and are reported as
 * such. Otherwise, and whenever there is no plan, the blocks are detected from the effort signal.
 */
public final class SessionStructure {

    public enum Source {
        /** Taken from the device's laps, which followed the planned workout. */
        DEVICE_LAPS,
        /** Detected from the effort signal. */
        DETECTED
    }

    public record Result(Source source, List<Block> work, List<List<Block>> repetitions,
                         List<StepComparison> againstPlan, String model) {
    }

    private SessionStructure() {
    }

    /**
     * @param deviceWork  work blocks as the device or Intervals.icu recorded them; may be empty
     * @param plan        planned work steps in order; empty when nothing was planned
     */
    public static Result of(List<Block> deviceWork, List<PlannedStep> plan, Samples effort, double endurancePlateau) {
        if (!plan.isEmpty() && !deviceWork.isEmpty() && followsPlan(deviceWork, plan)) {
            return result(Source.DEVICE_LAPS, deviceWork, plan, "device-laps");
        }
        Integer shortest = plan.stream().map(PlannedStep::seconds).min(Integer::compare).orElse(null);
        List<Block> detected = IntervalDetector.detect(effort, DetectionSettings.forPlan(shortest, endurancePlateau));
        return result(Source.DETECTED, detected, plan, IntervalDetector.MODEL);
    }

    static boolean followsPlan(List<Block> deviceWork, List<PlannedStep> plan) {
        long exact = PlanAlignment.align(plan, deviceWork).stream().filter(c ->
                c.planned() != null && c.done() != null
                        && Math.abs((double) c.done().seconds() / c.planned().seconds() - 1) <= LAP_MATCH).count();
        return exact * 2 >= plan.size();
    }

    /** A lap cut by the device's workout lands within this share of the planned duration. */
    static final double LAP_MATCH = 0.05;

    private static Result result(Source source, List<Block> work, List<PlannedStep> plan, String model) {
        List<StepComparison> comparison = plan.isEmpty() ? List.of() : PlanAlignment.align(plan, work);
        return new Result(source, work, Repetitions.group(work), comparison, model);
    }
}
