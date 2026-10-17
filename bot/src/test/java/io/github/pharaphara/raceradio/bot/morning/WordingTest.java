package io.github.pharaphara.raceradio.bot.morning;

import io.github.pharaphara.raceradio.core.readiness.DayRecord;
import io.github.pharaphara.raceradio.core.readiness.Feel;
import io.github.pharaphara.raceradio.core.readiness.Readiness;
import io.github.pharaphara.raceradio.core.readiness.ReadinessInput;
import io.github.pharaphara.raceradio.core.readiness.ReadinessModel;
import io.github.pharaphara.raceradio.core.readiness.Signal;
import io.github.pharaphara.raceradio.core.readiness.SignalKind;
import io.github.pharaphara.raceradio.core.readiness.Temperament;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The French wording must cover everything the readiness model can say, or the athlete would read
 * English in a degraded morning. The scenarios below make the model produce every signal kind and
 * every note, and nothing may come out untranslated.
 */
class WordingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    private static List<DayRecord> month(DayRecord today, double weekHrv) {
        List<DayRecord> h = new ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            double hrv = i <= 7 ? weekHrv : 50 * Math.exp(0.1 * Math.sin(i));
            h.add(new DayRecord(TODAY.minusDays(i), hrv, 48, 27_000, 80.0));
        }
        h.add(today);
        return h;
    }

    private static Readiness run(DayRecord today, double weekHrv, Double fitness, Double fatigue, double load3, Feel feel) {
        return run(today, weekHrv, fitness, fatigue, load3, feel, io.github.pharaphara.raceradio.core.readiness.HealthContext.NONE);
    }

    private static Readiness run(DayRecord today, double weekHrv, Double fitness, Double fatigue, double load3, Feel feel,
                                 io.github.pharaphara.raceradio.core.readiness.HealthContext h) {
        return ReadinessModel.assess(new ReadinessInput(month(today, weekHrv), fitness, fatigue, load3, feel, List.of(), h), Temperament.COMMITTED);
    }

    @Test
    void everySignalKindAndEveryNoteIsTranslated() {
        List<Readiness> scenarios = List.of(
                run(new DayRecord(TODAY, 22.0, 63, 3 * 3600, 19.0), 40, 30.0, 70.0, 500, new Feel(2, 2, 2, 2, 3)),
                run(new DayRecord(TODAY, 30.0, 48, 27_000, 80.0), 50, 40.0, 38.0, 0, null),
                run(new DayRecord(TODAY, 45.0, 48, 5 * 3600, 70.0), 50, 40.0, 38.0, 0, new Feel(4, 4, 4, 4, 4)),
                run(new DayRecord(TODAY, 50.0, 48, 27_000, 80.0), 50, 40.0, 38.0, 0, new Feel(4, 2, 2, 4, 4)),
                run(new DayRecord(TODAY, 22.0, 48, 3 * 3600, 19.0), 50, 40.0, 38.0, 0, new Feel(5, 5, 5, 5, 5)),
                run(new DayRecord(TODAY, 50.0, 43, 27_000, 80.0), 50, 40.0, 38.0, 0, null),
                run(new DayRecord(TODAY, null, null, null, null), 50, null, null, 0, null),
                run(new DayRecord(TODAY, 50.0, 48, 27_000, 80.0), 50, 40.0, 38.0, 0, null,
                        new io.github.pharaphara.raceradio.core.readiness.HealthContext(true, null, "ILLNESS")),
                run(new DayRecord(TODAY, 50.0, 48, 27_000, 80.0), 50, 40.0, 38.0, 0, null,
                        new io.github.pharaphara.raceradio.core.readiness.HealthContext(false, 2, "INJURY")),
                run(new DayRecord(TODAY, 50.0, 48, null, 80.0), 50, 40.0, 38.0, 0, null));

        Set<SignalKind> seen = EnumSet.noneOf(SignalKind.class);
        for (Readiness r : scenarios) {
            for (Signal s : r.signals()) {
                seen.add(s.kind());
                assertThat(Wording.signal(s)).doesNotContain("?");
            }
            for (String n : r.notes()) {
                assertThat(Wording.note(n)).as(n).doesNotStartWith("(note non traduite)");
            }
            for (String m : r.missing()) {
                assertThat(Wording.missing(m)).as(m).isNotEqualTo(m);
            }
            assertThat(Wording.deterministic(r)).startsWith(Wording.verdictLine(r.verdict()));
        }
        assertThat(seen).containsExactlyInAnyOrder(SignalKind.values());
    }

    @Test
    void sleepIsWrittenTheFrenchWay() {
        Signal s = new Signal(SignalKind.SLEEP, io.github.pharaphara.raceradio.core.readiness.Severity.MILD, "", Signal.Registry.CONVENTION, 5.5, 58.0);
        assertThat(Wording.signal(s)).contains("nuit de 5h30").contains("score de sommeil 58");
    }
}
