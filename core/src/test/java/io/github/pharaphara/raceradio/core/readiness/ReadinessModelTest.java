package io.github.pharaphara.raceradio.core.readiness;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static io.github.pharaphara.raceradio.core.readiness.Temperament.BALANCED;
import static io.github.pharaphara.raceradio.core.readiness.Temperament.CAUTIOUS;
import static io.github.pharaphara.raceradio.core.readiness.Temperament.COMMITTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fictional athlete with a steady month behind them: variability around 50 ms with small
 * day-to-day swings, resting heart rate 48, seven and a half hours of good sleep. Each test changes
 * this morning only, so that the verdict can be traced to the change.
 */
class ReadinessModelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final int GOOD_NIGHT = (int) (7.5 * 3600);

    private static List<DayRecord> steadyMonthThen(DayRecord today) {
        List<DayRecord> h = new ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            h.add(new DayRecord(TODAY.minusDays(i), 50 * Math.exp(0.1 * Math.sin(i)), 48, GOOD_NIGHT, 80.0));
        }
        h.add(today);
        return h;
    }

    private static DayRecord morning(Double hrv, Integer rhr, Integer sleep, Double score) {
        return new DayRecord(TODAY, hrv, rhr, sleep, score);
    }

    private static Readiness assess(DayRecord today, Temperament t) {
        return assess(today, null, t);
    }

    private static Readiness assess(DayRecord today, Feel feel, Temperament t) {
        return ReadinessModel.assess(new ReadinessInput(steadyMonthThen(today), 40.0, 38.0, 120, feel, List.of()), t);
    }

    @Test
    void anOrdinaryMorningIsGreenWithNothingToSay() {
        Readiness r = assess(morning(50.0, 48, GOOD_NIGHT, 80.0), COMMITTED);
        assertThat(r.verdict()).isEqualTo(Verdict.GREEN);
        assertThat(r.signals()).isEmpty();
        assertThat(r.score()).isEqualTo(100);
    }

    @Test
    void theFirstMorningOfAFeverIsRed() {
        // Variability collapses and the night falls apart, before resting heart rate has moved.
        Readiness r = assess(morning(22.0, 48, (int) (3.2 * 3600), 19.0), COMMITTED);
        assertThat(r.verdict()).isEqualTo(Verdict.RED);
        assertThat(r.signals()).extracting(Signal::kind).contains(SignalKind.HRV_TODAY, SignalKind.SLEEP);
    }

    @Test
    void aStomachBugShowingMostlyInRestingHeartRateIsRedToo() {
        // Variability only somewhat low, but resting heart rate up by fifteen and a short night.
        Readiness r = assess(morning(45.0, 63, (int) (5.8 * 3600), 43.0), COMMITTED);
        assertThat(r.verdict()).isEqualTo(Verdict.RED);
        assertThat(r.signals().getFirst().kind()).isEqualTo(SignalKind.RESTING_HEART_RATE);
        assertThat(r.signals().getFirst().evidence()).contains("63").contains("15 above");
    }

    @Test
    void aSingleIsolatedReadingIsMentionedButDoesNotChangeTheDayForACommittedAthlete() {
        DayRecord onlyHrvLow = morning(30.0, 48, GOOD_NIGHT, 80.0);
        Readiness committed = assess(onlyHrvLow, COMMITTED);
        assertThat(committed.verdict()).isEqualTo(Verdict.GREEN);
        assertThat(committed.notes()).anyMatch(n -> n.contains("single reading"));
        assertThat(assess(onlyHrvLow, BALANCED).verdict()).isEqualTo(Verdict.YELLOW);
    }

    @Test
    void twoConcordantMildSignalsEaseTheDay() {
        Readiness r = assess(morning(45.0, 48, (int) (5.5 * 3600), 70.0), COMMITTED);
        assertThat(r.signals()).hasSize(2).allMatch(s -> s.severity() == Severity.MILD);
        assertThat(r.verdict()).isEqualTo(Verdict.YELLOW);
    }

    @Test
    void feelingGoodLiftsAMildYellowDayBackToGreen() {
        Feel good = new Feel(4, 4, 4, 4, 4);
        Readiness r = assess(morning(45.0, 48, (int) (5.5 * 3600), 70.0), good, COMMITTED);
        assertThat(r.verdict()).isEqualTo(Verdict.GREEN);
        assertThat(r.notes()).anyMatch(n -> n.contains("trusts how you feel"));
    }

    @Test
    void feelingGoodDoesNotLiftARedDayBecauseAnIllnessCanFeelFineAtFirst() {
        Feel good = new Feel(5, 5, 5, 5, 5);
        Readiness r = assess(morning(22.0, 48, (int) (3.2 * 3600), 19.0), good, COMMITTED);
        assertThat(r.verdict()).isEqualTo(Verdict.RED);
        assertThat(r.notes()).anyMatch(n -> n.contains("feel fine on its first morning"));
    }

    @Test
    void feelingLowNeverLeavesTheDayGreenEvenWhenTheNumbersAreFine() {
        Feel heavyLegs = new Feel(4, 2, 2, 4, 4);
        Readiness r = assess(morning(50.0, 48, GOOD_NIGHT, 80.0), heavyLegs, COMMITTED);
        assertThat(r.verdict()).isEqualTo(Verdict.YELLOW);
    }

    @Test
    void poorSleepAndLowMoodTogetherReadAsExhaustion() {
        Feel exhausted = new Feel(2, 3, 4, 2, 4);
        Readiness r = assess(morning(50.0, 48, GOOD_NIGHT, 80.0), exhausted, COMMITTED);
        assertThat(r.signals()).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(SignalKind.FEEL);
            assertThat(s.severity()).isEqualTo(Severity.STRONG);
            assertThat(s.evidence()).contains("exhaustion");
        });
    }

    @Test
    void missingDataIsReportedAsMissingAndNeverInvented() {
        Readiness r = assess(morning(null, null, null, null), COMMITTED);
        assertThat(r.signals()).isEmpty();
        assertThat(r.missing()).hasSize(3);
    }

    @Test
    void noJudgementWithoutAFortnightOfBaseline() {
        List<DayRecord> shortHistory = steadyMonthThen(morning(22.0, 70, GOOD_NIGHT, 80.0)).subList(25, 31);
        Readiness r = ReadinessModel.assess(new ReadinessInput(shortHistory, null, null, 0, null, List.of()), COMMITTED);
        assertThat(r.signals()).isEmpty();
        assertThat(r.missing()).anyMatch(m -> m.contains("baseline"));
    }

    @Test
    void anUnusuallyLowRestingHeartRateIsANoteNotASignal() {
        Readiness r = assess(morning(50.0, 43, GOOD_NIGHT, 80.0), COMMITTED);
        assertThat(r.signals()).isEmpty();
        assertThat(r.verdict()).isEqualTo(Verdict.GREEN);
        assertThat(r.notes()).anyMatch(n -> n.contains("unusually low"));
    }

    @Test
    void conventionsAreLabelledAsConventions() {
        Readiness r = assess(morning(30.0, 63, GOOD_NIGHT, 80.0), COMMITTED);
        assertThat(r.signals()).filteredOn(s -> s.kind() == SignalKind.HRV_TODAY).allMatch(s -> s.registry() == Signal.Registry.STATISTICAL);
        assertThat(r.signals()).filteredOn(s -> s.kind() == SignalKind.RESTING_HEART_RATE).allMatch(s -> s.registry() == Signal.Registry.CONVENTION);
    }

    @Test
    void aMoreCautiousTemperamentIsNeverMoreLenient() {
        List<DayRecord> mornings = List.of(
                morning(50.0, 48, GOOD_NIGHT, 80.0), morning(45.0, 48, GOOD_NIGHT, 80.0),
                morning(30.0, 48, GOOD_NIGHT, 80.0), morning(45.0, 54, (int) (5.5 * 3600), 70.0),
                morning(22.0, 48, (int) (3.2 * 3600), 19.0), morning(45.0, 63, (int) (5.8 * 3600), 43.0));
        for (DayRecord m : mornings) {
            Verdict c = assess(m, CAUTIOUS).verdict();
            Verdict b = assess(m, BALANCED).verdict();
            Verdict k = assess(m, COMMITTED).verdict();
            assertThat(c).isGreaterThanOrEqualTo(b);
            assertThat(b).isGreaterThanOrEqualTo(k);
        }
    }

    @Test
    void theModelNamesItself() {
        assertThat(assess(morning(50.0, 48, GOOD_NIGHT, 80.0), COMMITTED).model()).isEqualTo("readiness/two-layer-graded");
    }
}
