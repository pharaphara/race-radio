package io.github.pharaphara.raceradio.bot.llm;

/**
 * @param answeringModel  the model that actually answered; with fallbacks on, it can differ from
 *                        the one requested, and so can the price
 * @param refused         the model declined; the caller falls back to its deterministic text
 * @param reportedCostUsd the cost as the transport itself reported it, at list price, or null when
 *                        it reports none; when present it is preferred to the configured prices
 */
public record ModelReply(String text, String answeringModel, String stopReason, boolean refused, Usage usage,
                         long latencyMs, Double reportedCostUsd) {
}
