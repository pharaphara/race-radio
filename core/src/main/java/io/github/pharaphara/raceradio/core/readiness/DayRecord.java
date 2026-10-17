package io.github.pharaphara.raceradio.core.readiness;

import java.time.LocalDate;

/**
 * One morning of recovery data. Any field may be missing: a night without the watch, a strap
 * that did not record. Missing data is reported as missing, never filled in.
 *
 * @param hrv           overnight heart rate variability, milliseconds (rMSSD as the device reports it)
 * @param restingHeartRate beats per minute
 * @param sleepSeconds  total sleep
 * @param sleepScore    the device's 0 to 100 sleep score
 */
public record DayRecord(LocalDate date, Double hrv, Integer restingHeartRate, Integer sleepSeconds, Double sleepScore) {
}
