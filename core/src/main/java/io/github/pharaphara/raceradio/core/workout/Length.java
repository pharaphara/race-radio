package io.github.pharaphara.raceradio.core.workout;

/** How long a step lasts: a time, or a distance (swimming sets are usually written in metres). */
public sealed interface Length {

    record Time(int seconds) implements Length {
        public Time {
            if (seconds < 1) {
                throw new IllegalArgumentException("a step lasts at least a second");
            }
        }
    }

    record Distance(int metres) implements Length {
        public Distance {
            if (metres < 1) {
                throw new IllegalArgumentException("a step covers at least a metre");
            }
        }
    }
}
