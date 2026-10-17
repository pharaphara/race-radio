package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Thresholds for one family of activity types. Intervals.icu states paces as speeds in metres per
 * second, which is also the unit the zone sets in core use.
 */
public record SportSettings(
        @JsonProperty("types") List<String> types,
        @JsonProperty("ftp") Integer thresholdPower,
        @JsonProperty("lthr") Integer thresholdHeartRate,
        @JsonProperty("threshold_pace") Double thresholdSpeed,
        @JsonProperty("w_prime") Integer wPrime) {

    public boolean covers(String activityType) {
        return types != null && types.contains(activityType);
    }
}
