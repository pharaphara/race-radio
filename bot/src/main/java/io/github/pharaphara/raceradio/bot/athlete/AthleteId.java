package io.github.pharaphara.raceradio.bot.athlete;

/**
 * Who a row, a file or a message belongs to. A type of its own rather than a bare {@code long}, so
 * that an athlete can never be confused with a chat id, an activity id or a count.
 */
public record AthleteId(long value) {

    public AthleteId {
        if (value <= 0) {
            throw new IllegalArgumentException("an athlete id is positive, not " + value);
        }
    }

    @Override
    public String toString() {
        return "athlete " + value;
    }
}
