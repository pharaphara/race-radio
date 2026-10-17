package io.github.pharaphara.raceradio.bot.review;

/** How numbers read in French: durations, paces, efforts in the unit of their sport. */
final class Formats {

    private Formats() {
    }

    static String duration(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return h + "h" + String.format("%02d", m);
        }
        if (m == 0) {
            return s + " s";
        }
        return s == 0 ? m + " min" : m + " min " + String.format("%02d", s) + " s";
    }

    /**
     * A repetition's length as a coach would say it: whole minutes from two minutes up, since a
     * device lap of 10 min 03 s is a ten-minute rep. Exact durations stay in the facts.
     */
    static String repDuration(int seconds) {
        return seconds >= 120 ? Math.round(seconds / 60.0) + " min" : duration(seconds);
    }

    /** Mean effort in the reader's unit: watts, pace per kilometre, or pace per 100 m. */
    static String effort(double value, String unit, String sport) {
        if (Double.isNaN(value)) {
            return "?";
        }
        if ("W".equals(unit)) {
            return Math.round(value) + " W";
        }
        if (value <= 0) {
            return "arrêt";
        }
        boolean swim = EffortSignal.SWIM.contains(sport);
        double secs = (swim ? 100 : 1000) / value;
        return (int) secs / 60 + ":" + String.format("%02d", Math.round(secs % 60) % 60) + (swim ? "/100 m" : "/km");
    }

    static String range(double low, double high, String unit, String sport) {
        if (Double.isNaN(low) || Double.isNaN(high)) {
            return "";
        }
        if ("W".equals(unit)) {
            return Math.round(low) + "-" + Math.round(high) + " W";
        }
        // Faster speed is the smaller pace: write the fast end first, as plans do.
        return effort(high, unit, sport).replaceAll("/.*", "") + "-" + effort(low, unit, sport);
    }
}
