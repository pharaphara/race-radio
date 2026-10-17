package io.github.pharaphara.raceradio.core.intervals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Lines the done blocks up against the planned work steps, in order, by dynamic programming: the
 * same alignment as an edit distance, where matching a block to a step costs the log of their
 * duration ratio plus their intensity gap, and leaving either side unmatched costs a fixed price.
 * This finds which rep was skipped, rather than assuming it was the last one.
 *
 * <p>It only describes. It never cuts, stretches or relabels a done block to fit the plan.
 */
public final class PlanAlignment {

    /** Durations within this share of the plan count as on plan. */
    static final double DURATION_TOLERANCE = 0.10;
    /** Leaving a step or a block unmatched costs as much as a block of double or half the length. */
    static final double GAP_COST = Math.log(2);
    /**
     * Outside these bounds a block is not that rep and cannot be matched to it. The lower bound is
     * looser than the upper one because a rep abandoned halfway is common and worth naming as such.
     */
    static final double MIN_DURATION_RATIO = 0.4;
    static final double MAX_DURATION_RATIO = 2.5;
    /**
     * Intensity within this share of the target counts as on target. Power meters are accurate to
     * one or two percent; calling 228 W "easier" than a 230 W floor would be reporting noise.
     */
    static final double INTENSITY_TOLERANCE = 0.03;

    private PlanAlignment() {
    }

    public static List<StepComparison> align(List<PlannedStep> plan, List<Block> done) {
        int n = plan.size();
        int m = done.size();
        double[][] cost = new double[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            cost[i][0] = i * GAP_COST;
        }
        for (int j = 1; j <= m; j++) {
            cost[0][j] = j * GAP_COST;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                double match = cost[i - 1][j - 1] + matchCost(plan.get(i - 1), done.get(j - 1));
                cost[i][j] = Math.min(match, Math.min(cost[i - 1][j] + GAP_COST, cost[i][j - 1] + GAP_COST));
            }
        }
        List<StepComparison> out = new ArrayList<>();
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + matchCost(plan.get(i - 1), done.get(j - 1))) {
                out.add(new StepComparison(plan.get(i - 1), done.get(j - 1), compare(plan.get(i - 1), done.get(j - 1))));
                i--;
                j--;
            } else if (i > 0 && cost[i][j] == cost[i - 1][j] + GAP_COST) {
                out.add(new StepComparison(plan.get(i - 1), null, EnumSet.of(Deviation.MISSING)));
                i--;
            } else {
                out.add(new StepComparison(null, done.get(j - 1), EnumSet.of(Deviation.EXTRA)));
                j--;
            }
        }
        Collections.reverse(out);
        return out;
    }

    static double matchCost(PlannedStep p, Block b) {
        double ratio = (double) b.seconds() / p.seconds();
        if (ratio > MAX_DURATION_RATIO || ratio < MIN_DURATION_RATIO) {
            return Double.POSITIVE_INFINITY;
        }
        double duration = Math.abs(Math.log(ratio));
        double intensity = 0;
        if (!Double.isNaN(p.targetLow()) && !Double.isNaN(p.targetHigh())) {
            double mid = (p.targetLow() + p.targetHigh()) / 2;
            intensity = Math.abs(b.mean() - mid) / mid;
        }
        return duration + intensity;
    }

    static EnumSet<Deviation> compare(PlannedStep p, Block b) {
        EnumSet<Deviation> d = EnumSet.noneOf(Deviation.class);
        double ratio = (double) b.seconds() / p.seconds();
        if (ratio > 1 + DURATION_TOLERANCE) {
            d.add(Deviation.LONGER);
        } else if (ratio < 1 - DURATION_TOLERANCE) {
            d.add(Deviation.SHORTER);
        }
        if (!Double.isNaN(p.targetHigh()) && b.mean() > p.targetHigh() * (1 + INTENSITY_TOLERANCE)) {
            d.add(Deviation.HARDER);
        } else if (!Double.isNaN(p.targetLow()) && b.mean() < p.targetLow() * (1 - INTENSITY_TOLERANCE)) {
            d.add(Deviation.EASIER);
        }
        if (d.isEmpty()) {
            d.add(Deviation.AS_PLANNED);
        }
        return d;
    }
}
