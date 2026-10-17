package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

public record Athlete(
        @JsonProperty("id") String id,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("sportSettings") List<SportSettings> sportSettings,
        @JsonProperty("icu_garmin_upload_workouts") Boolean garminUploadWorkouts,
        @JsonProperty("icu_garmin_training") Boolean garminTraining) {

    public Optional<SportSettings> settingsFor(String activityType) {
        return sportSettings == null ? Optional.empty()
                : sportSettings.stream().filter(s -> s.covers(activityType)).findFirst();
    }

    /**
     * Whether planned workouts reach the watch. When this is false, a perfectly valid workout
     * published to the calendar never arrives on the device, so publishing refuses to run.
     */
    public boolean pushesWorkoutsToGarmin() {
        return Boolean.TRUE.equals(garminUploadWorkouts);
    }
}
