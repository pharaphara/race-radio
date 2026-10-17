package io.github.pharaphara.raceradio.core.readiness;

/**
 * What the athlete reports, on five dimensions scored 1 (bad) to 5 (good). Soreness is scored the
 * same way round: 5 means no soreness. Five short answers say more than a single 1-to-10 mark,
 * and sleep and mood low together read as exhaustion rather than tired legs.
 */
public record Feel(int sleep, int energy, int soreness, int mood, int appetite) {

    public static final int LOW = 2;

    public Feel {
        for (int v : new int[] {sleep, energy, soreness, mood, appetite}) {
            if (v < 1 || v > 5) {
                throw new IllegalArgumentException("each dimension is scored 1 to 5");
            }
        }
    }

    public int lowDimensions() {
        int n = 0;
        for (int v : new int[] {sleep, energy, soreness, mood, appetite}) {
            if (v <= LOW) {
                n++;
            }
        }
        return n;
    }

    public boolean sleepAndMoodBothLow() {
        return sleep <= LOW && mood <= LOW;
    }

    /** Nothing low and a good overall picture. */
    public boolean good() {
        return lowDimensions() == 0 && (sleep + energy + soreness + mood + appetite) / 5.0 >= 3.5;
    }
}
