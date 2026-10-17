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
        @JsonProperty("category") String category,
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("external_id") String externalId,
        @JsonProperty("moving_time") Integer movingSeconds,
        @JsonProperty("icu_training_load") Integer load,
        @JsonProperty("workout_doc") JsonNode workoutDoc) {

    public static final String OWN_PREFIX = "race-radio-";

    public boolean ownedByRaceRadio() {
        return externalId != null && externalId.startsWith(OWN_PREFIX);
    }

    /**
     * Intervals.icu fills the workout document only when it understood the description. Without
     * it the workout does not sync to the watch, so a planned workout lacking one is a failure.
     */
    public boolean wasUnderstoodAsAWorkout() {
        return workoutDoc != null && !workoutDoc.isNull();
    }
}
