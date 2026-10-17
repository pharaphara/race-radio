package io.github.pharaphara.raceradio.core.swim;

import io.github.pharaphara.raceradio.core.series.Samples;

/**
 * Distance covered per stroke: the main efficiency indicator in swimming.
 *
 * <p>Swimming faster comes from longer strokes, a higher stroke rate, or both. Distance per stroke
 * isolates the first, which is what technique work moves. It is computed from the speed and the
 * stroke rate the watch records, and compared across sessions only at similar speed and heart rate.
 */
public final class StrokeEfficiency {

    private StrokeEfficiency() {
    }

    /**
     * @param speed       metres per second, second by second
     * @param strokeRate  strokes per minute as the device reports them, second by second
     * @return mean distance per stroke in metres over seconds where both readings exist and the
     *         swimmer is moving; NaN when there is no such second
     */
    public static double distancePerStroke(Samples speed, Samples strokeRate) {
        if (speed.length() != strokeRate.length()) {
            throw new IllegalArgumentException("speed and stroke rate must cover the same seconds");
        }
        double metres = 0;
        double strokes = 0;
        for (int i = 0; i < speed.length(); i++) {
            double v = speed.at(i);
            double rate = strokeRate.at(i);
            if (Double.isNaN(v) || Double.isNaN(rate) || v <= 0 || rate <= 0) {
                continue;
            }
            metres += v;
            strokes += rate / 60.0;
        }
        return strokes == 0 ? Double.NaN : metres / strokes;
    }
}
