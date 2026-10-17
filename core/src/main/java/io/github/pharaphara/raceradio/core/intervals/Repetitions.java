package io.github.pharaphara.raceradio.core.intervals;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups work blocks that look like repetitions of the same effort: duration and intensity both
 * within ten percent of the group's first block. A block that differs, such as a last rep held
 * seven minutes longer on purpose, stays alone rather than being folded into its neighbours.
 */
public final class Repetitions {

    static final double TOLERANCE = 0.10;

    private Repetitions() {
    }

    public static List<List<Block>> group(List<Block> blocks) {
        List<List<Block>> groups = new ArrayList<>();
        for (Block b : blocks) {
            List<Block> home = null;
            for (List<Block> g : groups) {
                Block first = g.getFirst();
                if (close(first.seconds(), b.seconds()) && close(first.mean(), b.mean())) {
                    home = g;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                groups.add(home);
            }
            home.add(b);
        }
        return groups.stream().map(List::copyOf).toList();
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= TOLERANCE * Math.max(Math.abs(a), Math.abs(b));
    }
}
