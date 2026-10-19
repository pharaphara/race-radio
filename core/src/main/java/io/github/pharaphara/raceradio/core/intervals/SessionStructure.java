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
            return fromLaps(deviceWork, plan, endurancePlateau);
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

    /** A lap outside the plan counts as work only from this long, and above the endurance plateau. */
    static final int EXTRA_WORK_SECONDS = 120;

    /**
     * The watch's laps, read through the plan: laps that match a planned step are the work, grouped
     * by the step they match (five laps of the planned 1 km are "5 ×", whatever their own small
     * differences); a lap outside the plan stays only if it is a real effort, at least two minutes as
     * hard as the easiest planned work. Warm-up jogging laps and the like then leave the summary.
     */
    static Result fromLaps(List<Block> laps, List<PlannedStep> plan, double endurancePlateau) {
        List<StepComparison> comparison = PlanAlignment.align(plan, laps);
        // An extra lap is an effort when it is at least as hard as the easiest planned work it sits among.
        double floor = comparison.stream().filter(c -> c.planned() != null && c.done() != null)
                .mapToDouble(c -> c.done().mean()).min().orElse(endurancePlateau);
        if (!Double.isNaN(endurancePlateau)) {
            floor = Math.max(floor, endurancePlateau);
        }
        List<Block> work = new java.util.ArrayList<>();
        List<List<Block>> groups = new java.util.ArrayList<>();
        PlannedStep previous = null;
        for (StepComparison c : comparison) {
            Block b = c.done();
            if (b == null) {
                previous = null;
                continue;
            }
            if (c.planned() != null) {
                work.add(b);
                // Same planned step and about the same length: a rep held much longer on purpose stands alone.
                if (c.planned().equals(previous) && !groups.isEmpty()
                        && Math.abs((double) b.seconds() / groups.getLast().getFirst().seconds() - 1) <= 0.10) {
                    groups.getLast().add(b);
                } else {
                    groups.add(new java.util.ArrayList<>(List.of(b)));
                }
                previous = c.planned();
            } else {
                previous = null;
                boolean effort = b.seconds() >= EXTRA_WORK_SECONDS && (Double.isNaN(floor) || b.mean() >= floor);
                if (effort) {
                    work.add(b);
                    groups.add(new java.util.ArrayList<>(List.of(b)));
                }
            }
        }
        return new Result(Source.DEVICE_LAPS, List.copyOf(work), groups.stream().map(List::copyOf).toList(), comparison, "device-laps");
    }

    private static Result result(Source source, List<Block> work, List<PlannedStep> plan, String model) {
        List<StepComparison> comparison = plan.isEmpty() ? List.of() : PlanAlignment.align(plan, work);
        return new Result(source, work, Repetitions.group(work), comparison, model);
    }
}
