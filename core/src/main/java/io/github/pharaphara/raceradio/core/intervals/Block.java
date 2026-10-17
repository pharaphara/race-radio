package io.github.pharaphara.raceradio.core.intervals;

/**
 * A stretch of a session, as indices into its one-per-second streams: {@code [start, end)}.
 *
 * @param mean  mean effort over the block, in the unit of the signal it was detected on
 */
public record Block(int start, int end, double mean) {

    public Block {
        if (end <= start) {
            throw new IllegalArgumentException("a block lasts at least one second");
        }
    }

    public int seconds() {
        return end - start;
    }
}
