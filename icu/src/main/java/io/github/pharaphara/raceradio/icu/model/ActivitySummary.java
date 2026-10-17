package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

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
        @JsonProperty("icu_average_watts") Double averagePower) {
}
