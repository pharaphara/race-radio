package io.github.pharaphara.raceradio.core.zones;

/**
 * One training zone: a half-open range {@code [lower, upper)} in the zone set's unit.
 *
 * <p>For pace-based zones the unit is speed in metres per second, never seconds per kilometre, so
 * that "higher is harder" holds for every sport and a single containment test serves them all.
 */
public record Zone(String label, String name, double lower, double upper) {

    public Zone {
        if (!(upper > lower)) {
            throw new IllegalArgumentException("zone " + label + " is empty or inverted");
        }
    }

    public boolean contains(double value) {
        return value >= lower && value < upper;
    }
}
