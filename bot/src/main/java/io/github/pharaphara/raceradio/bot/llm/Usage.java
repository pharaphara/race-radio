package io.github.pharaphara.raceradio.bot.llm;

/**
 * Tokens of one call. Input counts only the uncached part; cache writes and reads are separate,
 * because they are priced differently and because a cache that silently stops working shows up
 * as reads falling to zero while plain input climbs.
 */
public record Usage(long input, long output, long cacheWrite, long cacheRead) {

    public static final Usage NONE = new Usage(0, 0, 0, 0);
}
