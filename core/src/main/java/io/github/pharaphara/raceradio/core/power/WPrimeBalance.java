package io.github.pharaphara.raceradio.core.power;

import io.github.pharaphara.raceradio.core.series.Samples;

/**
 * Second-by-second balance of W′, the finite work capacity above critical power.
 *
 * <p>Uses the differential form of the model (Skiba's balance, in the differential formulation
 * popularised by Froncioni and Clarke): above critical power the reserve drains joule for joule;
 * below it the reserve refills in proportion to how far below critical power you ride and to how
 * empty the reserve is. Unlike the integral form it needs no fitted time constant.
 *
 * <p>Which model produced a W′ figure matters: a 2023 comparison across platforms found
 * significant differences in the critical power and W′ that different models estimate. This class
 * therefore names itself in {@link #MODEL}, and figures from different models are never compared.
 *
 * <p>The balance is allowed to go below zero. That is not a bug to clamp away but information:
 * a ride that drains more than the declared W′ means the declared critical power or W′ is too low.
 */
public final class WPrimeBalance {

    public static final String MODEL = "w-prime-balance/differential";

    private WPrimeBalance() {
    }

    /**
     * @param power          one reading per second, watts; missing readings count as recovery at zero watts
     * @param criticalPower  watts; threshold power is the usual stand-in when no critical power test exists
     * @param wPrime         joules
     */
    public static Result compute(Samples power, double criticalPower, double wPrime) {
        if (criticalPower <= 0 || wPrime <= 0) {
            throw new IllegalArgumentException("critical power and W' must be positive");
        }
        double[] balance = new double[power.length()];
        double current = wPrime;
        double lowest = wPrime;
        int lowestAt = 0;
        for (int i = 0; i < balance.length; i++) {
            double p = Double.isNaN(power.at(i)) ? 0 : power.at(i);
            if (p > criticalPower) {
                current -= p - criticalPower;
            } else {
                current += (criticalPower - p) * (wPrime - current) / wPrime;
            }
            balance[i] = current;
            if (current < lowest) {
                lowest = current;
                lowestAt = i;
            }
        }
        return new Result(Samples.of(balance), lowest, lowestAt, wPrime);
    }

    /**
     * @param balance   joules remaining, second by second
     * @param lowest    deepest point reached, joules
     * @param lowestAt  second at which it was reached
     * @param wPrime    the capacity the computation started from
     */
    public record Result(Samples balance, double lowest, int lowestAt, double wPrime) {

        /** Share of W′ spent at the deepest point, 0 to 1, above 1 when the reserve went negative. */
        public double deepestDepletion() {
            return (wPrime - lowest) / wPrime;
        }

        /** True when the ride used more than the declared capacity: a hint the profile is stale. */
        public boolean exceededDeclaredCapacity() {
            return lowest < 0;
        }
    }
}
