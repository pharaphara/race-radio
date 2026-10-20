package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEditsTest {

    /** A Tuesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 22);

    private static CalendarEvent event(long id, String type, String name, String externalId) {
        return new CalendarEvent(id, THURSDAY.atStartOfDay(), null, "WORKOUT", type, name, "", externalId, null, null, null);
    }

    @Test
    void theDaysComeInOrderWhateverTheWordsAround() {
        var r = CalendarEdits.parse("la séance de jeudi à vendredi, le vélo", TODAY);
        assertThat(r.days()).containsExactly(THURSDAY, THURSDAY.plusDays(1));
        assertThat(r.sport()).isEqualTo("vélo");
        var iso = CalendarEdits.parse("2026-10-22 2026-10-24", TODAY);
        assertThat(iso.days()).containsExactly(THURSDAY, THURSDAY.plusDays(2));
        assertThat(iso.sport()).isNull();
    }

    @Test
    void aChangeKeepsItsOwnWordsAndNeverReadsMarcheAsTuesday() {
        var r = CalendarEdits.parse("demain plus court, marche en côte", TODAY);
        assertThat(r.days()).containsExactly(TODAY.plusDays(1));
        assertThat(r.rest()).isEqualTo("plus court, marche en côte");
    }

    @Test
    void onlyTheCoachsOwnWorkoutsCanBeTouched() {
        var garmin = event(1, "Run", "Footing Garmin Coach", null);
        var own = event(2, "Ride", "Vélo seuil · 3 × 10 min", "race-radio-x");
        assertThat(CalendarEdits.choose(List.of(garmin), THURSDAY, null).message()).contains("ne vient pas de moi");
        assertThat(CalendarEdits.choose(List.of(garmin, own), THURSDAY, null).events()).containsExactly(own);
        assertThat(CalendarEdits.choose(List.of(), THURSDAY, null).message()).startsWith("Rien de prévu");
    }

    @Test
    void aSportPicksOneOfTwoWorkoutsTheSameDay() {
        var ride = event(2, "Ride", "Vélo endurance", "race-radio-a");
        var run = event(3, "Run", "Course enchaînée", "race-radio-b");
        assertThat(CalendarEdits.choose(List.of(ride, run), THURSDAY, "course").events()).containsExactly(run);
        assertThat(CalendarEdits.choose(List.of(ride, run), THURSDAY, null).events()).hasSize(2);
        assertThat(CalendarEdits.choose(List.of(ride), THURSDAY, "natation").message()).contains("Pas de séance de natation");
    }
}
