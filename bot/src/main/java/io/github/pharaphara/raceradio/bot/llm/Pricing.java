package io.github.pharaphara.raceradio.bot.llm;

import io.github.pharaphara.raceradio.bot.config.RaceRadioProperties;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/** Turns token counts into an estimated cost, from the prices in the configuration. */
public final class Pricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, RaceRadioProperties.Prices> prices;

    public Pricing(Map<String, RaceRadioProperties.Prices> prices) {
        this.prices = Map.copyOf(prices);
    }

    /** Dollars. A model missing from the price list costs nothing here, and the ledger says so. */
    public BigDecimal cost(String model, Usage u) {
        RaceRadioProperties.Prices p = prices.get(model);
        if (p == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal micro = p.input().multiply(BigDecimal.valueOf(u.input()))
                .add(p.output().multiply(BigDecimal.valueOf(u.output())))
                .add(p.cacheWrite().multiply(BigDecimal.valueOf(u.cacheWrite())))
                .add(p.cacheRead().multiply(BigDecimal.valueOf(u.cacheRead())));
        return micro.divide(MILLION, MathContext.DECIMAL64);
    }

    public boolean knows(String model) {
        return prices.containsKey(model);
    }
}
