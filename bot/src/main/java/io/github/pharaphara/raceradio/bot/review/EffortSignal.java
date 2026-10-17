package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.SportSettings;

import java.util.Optional;
import java.util.Set;

/**
 * The signal a session's structure is read from, and the plateau below which nothing is work.
 *
 * <p>Cycling: power. Running: speed, because running power is not recorded by every watch and
 * the endurance plateau is defined on pace; on hilly ground the device's laps take over anyway.
 * Swimming: speed. The plateau is the top of the endurance zone: three quarters of threshold power,
 * four fifths of threshold speed.
 */
public record EffortSignal(String column, Samples values, String unit, double plateau) {

    static final Set<String> BIKE = Set.of("Ride", "VirtualRide", "GravelRide", "MountainBikeRide");
    static final Set<String> RUN = Set.of("Run", "TrailRun", "VirtualRun");
    static final Set<String> SWIM = Set.of("Swim", "OpenWaterSwim");

    static final double BIKE_PLATEAU = 0.75;
    static final double PACE_PLATEAU = 0.80;
    /** Below this share of seconds with a reading, a stream is not usable. */
    static final double MIN_COVERAGE = 0.5;

    public static Optional<EffortSignal> of(String sport, ActivityStreams streams, Optional<SportSettings> settings) {
        if (BIKE.contains(sport)) {
            double plateau = settings.map(SportSettings::thresholdPower).map(ftp -> BIKE_PLATEAU * ftp).orElse(Double.NaN);
            return usable(streams, "watts").map(s -> new EffortSignal("watts", s, "W", plateau));
        }
        if (RUN.contains(sport) || SWIM.contains(sport)) {
            double plateau = settings.map(SportSettings::thresholdSpeed).map(v -> PACE_PLATEAU * v).orElse(Double.NaN);
            return usable(streams, "velocity_smooth").map(s -> new EffortSignal("velocity_smooth", s, "m/s", plateau));
        }
        return Optional.empty();
    }

    private static Optional<Samples> usable(ActivityStreams streams, String column) {
        return streams.get(column).filter(s -> {
            int nonZero = 0;
            for (int i = 0; i < s.length(); i++) {
                if (!Double.isNaN(s.at(i)) && s.at(i) > 0) {
                    nonZero++;
                }
            }
            return s.length() > 0 && nonZero >= MIN_COVERAGE * s.length();
        });
    }
}
