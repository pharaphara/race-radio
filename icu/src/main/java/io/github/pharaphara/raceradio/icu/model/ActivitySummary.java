package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * An activity as Intervals.icu lists it.
 *
 * @param rpe             effort rating out of 10 entered on the watch, synced from Garmin; null when not entered
 * @param feel            how the athlete felt, Intervals.icu's scale from 1 (strong) to 5 (weak); null when not entered
 * @param averageCadence  as the device records it: per leg on foot, per minute on the bike
 */
public record ActivitySummary(
        @JsonProperty("id") String id,
        @JsonProperty("start_date_local") LocalDateTime start,
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("moving_time") Integer movingSeconds,
        @JsonProperty("distance") Double distanceMetres,
        @JsonProperty("total_elevation_gain") Double elevationGainMetres,
        @JsonProperty("icu_training_load") Integer load,
        @JsonProperty("average_heartrate") Double averageHeartRate,
        @JsonProperty("icu_average_watts") Double averagePower,
        @JsonProperty("icu_rpe") Integer rpe,
        @JsonProperty("feel") Integer feel,
        @JsonProperty("average_temp") Double averageTemperature,
        @JsonProperty("average_cadence") Double averageCadence,
        @JsonProperty("average_speed") Double averageSpeed) {

    public ActivitySummary(String id, LocalDateTime start, String type, String name, Integer movingSeconds, Double distanceMetres,
                           Double elevationGainMetres, Integer load, Double averageHeartRate, Double averagePower) {
        this(id, start, type, name, movingSeconds, distanceMetres, elevationGainMetres, load, averageHeartRate, averagePower,
                null, null, null, null, null);
    }
}
