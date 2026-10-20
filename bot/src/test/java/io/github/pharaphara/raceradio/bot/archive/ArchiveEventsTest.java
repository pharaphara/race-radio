package io.github.pharaphara.raceradio.bot.archive;

import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveEventsTest {

    @TempDir
    Path dir;

    @Test
    void aDaysWorkoutsAreReadBackWithTheirOwnerWhateverElseTheCalendarSent() {
        AthleteDb jdbc = TestDb.in(dir);
        String own = """
                {"id":7,"start_date_local":"2026-10-22T00:00:00","category":"WORKOUT","type":"Ride","name":"Vélo seuil",
                 "external_id":"race-radio-x","icu_ftp":250,"target":"POWER","workout_doc":{"steps":[{"duration":600}]}}""";
        String note = """
                {"id":8,"start_date_local":"2026-10-22T00:00:00","category":"NOTE","name":"Vert","external_id":"race-radio-y"}""";
        String other = """
                {"id":9,"start_date_local":"2026-10-22T00:00:00","category":"WORKOUT","type":"Run","name":"Footing","external_id":"rot-1"}""";
        for (String e : new String[]{own, note, other}) {
            jdbc.jdbc().update("INSERT INTO archive_event (athlete_id, id, day, json, fetched_at) VALUES (?, ?, '2026-10-22', ?, '2026-10-20T06:00:00Z')",
                    jdbc.id(), e.contains("\"id\":7") ? 7 : e.contains("\"id\":8") ? 8 : 9, e);
        }
        Archive archive = new Archive(jdbc, null, null, Clock.systemUTC());
        assertThat(archive.workoutsOn(LocalDate.of(2026, 10, 22))).extracting(CalendarEvent::id).containsExactly(7L, 9L);
        CalendarEvent read = archive.workoutEvent(7).orElseThrow();
        assertThat(read.ownedByRaceRadio()).isTrue();
        assertThat(read.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(archive.workoutEvent(9).orElseThrow().ownedByRaceRadio()).isFalse();
    }
}
