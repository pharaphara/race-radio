package io.github.pharaphara.raceradio.core.workout;

/**
 * What a step asks for. A step is prescribed only in a unit its sport can measure for this athlete:
 * watts or power zones on the bike, heart-rate zones or pace on foot, pace per 100 m in the pool.
 */
public sealed interface Target {

    /** Watts, a range; low equals high for a single value. */
    record Power(int low, int high) implements Target {
        public Power {
            check(low > 0 && high >= low, "power range");
        }
    }

    /** Power zone number, 1 to 7. */
    record PowerZone(int zone) implements Target {
        public PowerZone {
            check(zone >= 1 && zone <= 7, "power zone");
        }
    }

    /** Heart-rate zone number, 1 to 7. */
    record HeartRateZone(int zone) implements Target {
        public HeartRateZone {
            check(zone >= 1 && zone <= 7, "heart-rate zone");
        }
    }

    /** Running pace in seconds per kilometre; {@code fast} is the smaller number. */
    record RunPace(int fast, int slow) implements Target {
        public RunPace {
            check(fast > 0 && slow >= fast, "run pace range");
        }
    }

    /** Swimming pace in seconds per 100 m; {@code fast} is the smaller number. */
    record SwimPace(int fast, int slow) implements Target {
        public SwimPace {
            check(fast > 0 && slow >= fast, "swim pace range");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid " + what);
        }
    }
}
