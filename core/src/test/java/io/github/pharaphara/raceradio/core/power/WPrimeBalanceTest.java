package io.github.pharaphara.raceradio.core.power;

import io.github.pharaphara.raceradio.core.series.Samples;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WPrimeBalanceTest {

    private static final double CP = 250;
    private static final double W_PRIME = 18_000;

    @Test
    void ridingAtCriticalPowerNeitherDrainsNorRefills() {
        WPrimeBalance.Result r = WPrimeBalance.compute(Samples.constant(CP, 600), CP, W_PRIME);
        assertThat(r.lowest()).isCloseTo(W_PRIME, within(1e-9));
        assertThat(r.deepestDepletion()).isCloseTo(0, within(1e-9));
    }

    @Test
    void aboveCriticalPowerTheReserveDrainsJouleForJoule() {
        // 90 W over critical power for 100 s spends exactly 9 kJ, half the reserve.
        WPrimeBalance.Result r = WPrimeBalance.compute(Samples.constant(CP + 90, 100), CP, W_PRIME);
        assertThat(r.lowest()).isCloseTo(9_000, within(1e-9));
        assertThat(r.deepestDepletion()).isCloseTo(0.5, within(1e-9));
        assertThat(r.lowestAt()).isEqualTo(99);
    }

    @Test
    void easyRidingRefillsTheReserveTowardsFullWithoutOvershooting() {
        Samples effortThenRest = Samples.concat(Samples.constant(CP + 100, 100), Samples.constant(100, 1800));
        WPrimeBalance.Result r = WPrimeBalance.compute(effortThenRest, CP, W_PRIME);
        double end = r.balance().at(r.balance().length() - 1);
        assertThat(end).isGreaterThan(17_000).isLessThanOrEqualTo(W_PRIME);
    }

    @Test
    void drainingPastTheDeclaredCapacityIsReportedNotHidden() {
        // 200 W over critical power for 150 s asks for 30 kJ from an 18 kJ reserve.
        WPrimeBalance.Result r = WPrimeBalance.compute(Samples.constant(CP + 200, 150), CP, W_PRIME);
        assertThat(r.exceededDeclaredCapacity()).isTrue();
        assertThat(r.deepestDepletion()).isGreaterThan(1.0);
    }

    @Test
    void theModelNamesItselfSoFiguresFromDifferentModelsAreNeverMixed() {
        assertThat(WPrimeBalance.MODEL).isEqualTo("w-prime-balance/differential");
    }
}
