package io.github.pharaphara.raceradio.icu.model;

import io.github.pharaphara.raceradio.core.series.Samples;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every stream of one activity, one sample per recorded second, keyed by Intervals.icu's column
 * name ({@code watts}, {@code heartrate}, {@code velocity_smooth}, {@code lat}, {@code lng},
 * {@code step_length}, ...). Which columns exist depends on the sport and the sensors.
 */
public record ActivityStreams(Map<String, Samples> columns) {

    /** Keeps the export's column order, which makes logs and charts predictable. */
    public ActivityStreams {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    public Optional<Samples> get(String column) {
        return Optional.ofNullable(columns.get(column));
    }

    public Set<String> names() {
        return columns.keySet();
    }

    public int length() {
        return columns.values().stream().mapToInt(Samples::length).max().orElse(0);
    }
}
