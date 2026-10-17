package io.github.pharaphara.raceradio.core.intervals;

import java.util.Set;

/**
 * One line of the planned-against-done comparison. Either side may be null: a missing rep has a
 * plan and no block, an extra effort has a block and no plan.
 */
public record StepComparison(PlannedStep planned, Block done, Set<Deviation> deviations) {

    public StepComparison {
        deviations = Set.copyOf(deviations);
    }
}
