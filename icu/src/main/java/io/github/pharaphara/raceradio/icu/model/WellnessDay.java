package io.github.pharaphara.raceradio.icu.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * One day of wellness and load. Wire names are Intervals.icu's; the Java names use the project's
 * public vocabulary (fitness, fatigue, ramp).
 */
public record WellnessDay(
        @JsonProperty("id") LocalDate date,
        @JsonProperty("ctl") Double fitness,
        @JsonProperty("atl") Double fatigue,
        @JsonProperty("rampRate") Double ramp,
        @JsonProperty("restingHR") Integer restingHeartRate,
        @JsonProperty("hrv") Double hrv,
        @JsonProperty("sleepSecs") Integer sleepSeconds,
        @JsonProperty("sleepScore") Double sleepScore,
        @JsonProperty("sleepQuality") Integer sleepQuality,
        @JsonProperty("weight") Double weight,
        @JsonProperty("vo2max") Double vo2max,
        @JsonProperty("steps") Integer steps) {

    /** Form is fitness minus fatigue, as the app shows it. Null when either is missing. */
    public Double form() {
        return fitness == null || fatigue == null ? null : fitness - fatigue;
    }
}
