package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * A calendar entry: a planned workout, a note, a race, a sick day.
 *
 * <p>{@code external_id} tells who wrote it. The coach writes {@code race-radio-<uuid>} and only
 * ever modifies or deletes entries carrying that prefix, so workouts from other tools and entries
 * the athlete made by hand are out of its reach.
 */
public record CalendarEvent(
        @JsonProperty("id") Long id,
        @JsonProperty("start_date_local") LocalDateTime start,
        @JsonProperty("end_date_local") LocalDateTime end,
        @JsonProperty("category") String category,
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("external_id") String externalId,
        @JsonProperty("moving_time") Integer movingSeconds,
        @JsonProperty("icu_training_load") Integer load,
        @JsonProperty("workout_doc") JsonNode workoutDoc) {

    public static final String OWN_PREFIX = "race-radio-";

    /** An illness or injury marked on the calendar. */
    public boolean isHealthMarker() {
        return "SICK".equals(category) || "INJURED".equals(category);
    }

    /**
     * Last calendar day the entry covers. Intervals.icu stores an exclusive end, so a one-day
     * marker ends at midnight the next day; without an end, the entry covers its start day.
     */
    public java.time.LocalDate lastDay() {
        if (end == null) {
            return start == null ? null : start.toLocalDate();
        }
        return end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? end.toLocalDate().minusDays(1) : end.toLocalDate();
    }

    public boolean ownedByRaceRadio() {
        return externalId != null && externalId.startsWith(OWN_PREFIX);
    }

    /**
     * Intervals.icu fills the workout document from the description, and that document is what the
     * watch receives. It must exist <em>and</em> hold steps: an empty description yields a document
     * that is present but has no step and lasts zero seconds, which arrives on the watch as an
     * empty workout. Both count as not understood.
     */
    public boolean wasUnderstoodAsAWorkout() {
        return workoutDoc != null && !workoutDoc.isNull()
                && workoutDoc.path("steps").isArray() && !workoutDoc.path("steps").isEmpty();
    }
}
