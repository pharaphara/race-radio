package io.github.pharaphara.raceradio.core.readiness;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthContextTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    private static Readiness assess(DayRecord today, HealthContext h) {
        List<DayRecord> history = new ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            history.add(new DayRecord(TODAY.minusDays(i), 50 * Math.exp(0.1 * Math.sin(i)), 48, 27_000, 80.0));
        }
        history.add(today);
        return ReadinessModel.assess(new ReadinessInput(history, 40.0, 38.0, 0, null, List.of(), h), Temperament.COMMITTED);
    }

    private static final DayRecord FINE = new DayRecord(TODAY, 50.0, 48, 27_000, 80.0);
    private static final DayRecord FEVER = new DayRecord(TODAY, 22.0, 48, 3 * 3600, 19.0);

    @Test
    void anActiveMarkerRulesOutAnUnqualifiedGreen() {
        Readiness r = assess(FINE, new HealthContext(true, null, "ILLNESS"));
        assertThat(r.verdict()).isEqualTo(Verdict.YELLOW);
        assertThat(r.notes()).anyMatch(n -> n.contains("not clearance"));
    }

    @Test
    void itNeverMakesTheDayEasierThanTheReadingsSay() {
        assertThat(assess(FEVER, new HealthContext(true, null, "ILLNESS")).verdict()).isEqualTo(Verdict.RED);
    }

    @Test
    void aRecentMarkerAsksWithoutChangingTheDay() {
        Readiness r = assess(FINE, new HealthContext(false, 3, "INJURY"));
        assertThat(r.verdict()).isEqualTo(Verdict.GREEN);
        assertThat(r.notes()).anyMatch(n -> n.contains("does not mean recovery"));
    }

    @Test
    void anOldMarkerIsIgnored() {
        assertThat(assess(FINE, new HealthContext(false, 20, "ILLNESS")).notes()).isEmpty();
    }
}
