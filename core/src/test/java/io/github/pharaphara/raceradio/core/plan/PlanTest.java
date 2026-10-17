package io.github.pharaphara.raceradio.core.plan;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;

class PlanTest {

    private static final LocalDate MONDAY_DATE = LocalDate.of(2026, 11, 16);
    private static final Thresholds FICTIONAL = new Thresholds(250, 270, 130);
    private static final Set<String> TRI = Set.of("Swim", "Ride", "Run");

    private static Map<DayOfWeek, Integer> week() {
        Map<DayOfWeek, Integer> m = new EnumMap<>(DayOfWeek.class);
        m.put(MONDAY, 60);
        m.put(WEDNESDAY, 45);
        m.put(THURSDAY, 60);
        m.put(SATURDAY, 120);
        m.put(SUNDAY, 90);
        return m;
    }

    @Test
    void theSeasonRunsBaseThenSpecificThenTaperWithARecoveryWeekEveryFourth() {
        List<Objective> race = List.of(new Objective(Objective.Kind.RACE, "Triathlon", LocalDate.of(2027, 5, 23), Objective.Priority.A, "< 2h40"));
        LocalDate season = LocalDate.of(2026, 11, 9);
        assertThat(Phase.of(LocalDate.of(2026, 11, 9), race, season)).isEqualTo(Phase.BASE);
        assertThat(Phase.of(LocalDate.of(2026, 11, 30), race, season)).isEqualTo(Phase.RECOVERY_WEEK);
        assertThat(Phase.of(LocalDate.of(2027, 4, 5), race, season)).isEqualTo(Phase.SPECIFIC);
        assertThat(Phase.of(LocalDate.of(2027, 5, 10), race, season)).isEqualTo(Phase.TAPER);
        assertThat(Phase.of(LocalDate.of(2027, 5, 17), race, season)).isEqualTo(Phase.RACE_WEEK);
    }

    @Test
    void withoutARaceTheSeasonIsMaintenance() {
        List<Objective> none = List.of(new Objective(Objective.Kind.FITNESS, "Any", null, Objective.Priority.A, "rester en forme"));
        assertThat(Phase.of(MONDAY_DATE, none, MONDAY_DATE)).isEqualTo(Phase.MAINTENANCE);
    }

    @Test
    void aBaseWeekHasOneHardBikeSessionAndRespectsTheTimeAvailable() {
        List<PlannedSession> w = WeekBuilder.build(MONDAY_DATE, Phase.BASE, week(), TRI, FICTIONAL);
        assertThat(w.stream().filter(PlannedSession::hard)).singleElement()
                .satisfies(s -> assertThat(s.workout().sport()).isEqualTo("Ride"));
        Map<DayOfWeek, Integer> available = week();
        for (PlannedSession s : w) {
            assertThat(s.minutes()).as(s.workout().name()).isLessThanOrEqualTo(available.get(s.day().getDayOfWeek()) + 5);
        }
        assertThat(w).filteredOn(s -> s.role() == PlannedSession.Role.LONG).singleElement()
                .satisfies(s -> assertThat(s.day().getDayOfWeek()).isEqualTo(SATURDAY));
        assertThat(w).filteredOn(s -> s.workout().sport().equals("Swim")).hasSize(2);
    }

    @Test
    void theSpecificPhasePutsABrickOnTheLongDayAndNoTwoHardDaysInARow() {
        List<PlannedSession> w = WeekBuilder.build(MONDAY_DATE, Phase.SPECIFIC, week(), TRI, FICTIONAL);
        assertThat(w).filteredOn(s -> s.role() == PlannedSession.Role.BRICK).hasSize(2)
                .allSatisfy(s -> assertThat(s.day().getDayOfWeek()).isEqualTo(SATURDAY));
        List<DayOfWeek> hardDays = w.stream().filter(PlannedSession::hard).map(s -> s.day().getDayOfWeek()).distinct().toList();
        assertThat(hardDays).hasSizeLessThanOrEqualTo(2);
        for (DayOfWeek a : hardDays) {
            for (DayOfWeek b : hardDays) {
                if (a != b) {
                    assertThat(Math.abs(a.getValue() - b.getValue())).isNotEqualTo(1);
                }
            }
        }
    }

    @Test
    void aRecoveryWeekIsShorterAndHasNoHardSession() {
        int full = WeekBuilder.build(MONDAY_DATE, Phase.BASE, week(), TRI, FICTIONAL).stream().mapToInt(PlannedSession::minutes).sum();
        List<PlannedSession> rec = WeekBuilder.build(MONDAY_DATE, Phase.RECOVERY_WEEK, week(), TRI, FICTIONAL);
        assertThat(rec).noneMatch(PlannedSession::hard);
        assertThat(rec.stream().mapToInt(PlannedSession::minutes).sum()).isLessThan((int) (full * 0.7));
    }

    @Test
    void swimmingOffForWinterMeansNoSwimAtAll() {
        assertThat(WeekBuilder.build(MONDAY_DATE, Phase.BASE, week(), Set.of("Ride", "Run"), FICTIONAL))
                .noneMatch(s -> s.workout().sport().equals("Swim"));
    }

    @Test
    void aSportWithoutAThresholdIsNotPrescribed() {
        assertThat(WeekBuilder.build(MONDAY_DATE, Phase.BASE, week(), TRI, new Thresholds(null, 270, null)))
                .allMatch(s -> s.workout().sport().equals("Run"));
    }

    @Test
    void fromSixAvailableDaysOneStaysFree() {
        Map<DayOfWeek, Integer> every = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            every.put(d, d == FRIDAY ? 30 : 60);
        }
        every.put(TUESDAY, 50);
        List<PlannedSession> w = WeekBuilder.build(MONDAY_DATE, Phase.BASE, every, TRI, FICTIONAL);
        assertThat(w.stream().map(s -> s.day().getDayOfWeek()).distinct().count()).isLessThanOrEqualTo(6);
        assertThat(w).noneMatch(s -> s.day().getDayOfWeek() == FRIDAY);
    }

    @Test
    void feasibilityNamesTheCeilingThatBinds() {
        Feasibility fewHours = Feasibility.of(39, 6, 30, 5);
        assertThat(fewHours.limitedBy()).isEqualTo("time");
        assertThat(fewHours.plateau()).isCloseTo(6 * 55 / 7.0, org.assertj.core.api.Assertions.within(1e-9));
        Feasibility soon = Feasibility.of(39, 12, 4, 5);
        assertThat(soon.limitedBy()).isEqualTo("ramp");
        assertThat(soon.byDate()).isEqualTo(59);
    }
}
