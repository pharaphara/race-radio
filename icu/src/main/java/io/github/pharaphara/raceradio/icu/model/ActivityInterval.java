package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An interval as Intervals.icu detected or stored it; indices point into the activity's streams. */
public record ActivityInterval(
        @JsonProperty("type") String type,
        @JsonProperty("label") String label,
        @JsonProperty("start_index") Integer startIndex,
        @JsonProperty("end_index") Integer endIndex,
        @JsonProperty("moving_time") Integer movingSeconds,
        @JsonProperty("distance") Double distanceMetres,
        @JsonProperty("average_watts") Double averagePower,
        @JsonProperty("average_heartrate") Double averageHeartRate,
        @JsonProperty("average_speed") Double averageSpeed,
        @JsonProperty("group_id") String groupId) {

    public boolean isWork() {
        return "WORK".equals(type);
    }
}
