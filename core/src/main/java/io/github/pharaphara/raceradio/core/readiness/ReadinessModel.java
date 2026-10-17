package io.github.pharaphara.raceradio.core.readiness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Train, train lighter, or recover.
 *
 * <p>Two layers, because they catch different things. The <em>trend</em> layer compares the mean
 * log heart rate variability of the seven mornings before today with the athlete's baseline: it
 * sees accumulated
 * fatigue but moves too slowly to catch an illness on its first morning. The <em>acute</em> layer
 * compares this morning's readings with the same baseline: it sees the first morning of a fever
 * or a stomach bug, when variability collapses, resting heart rate jumps or sleep falls apart.
 * Replayed on a year of real data, neither layer alone flagged both recorded illnesses on their
 * first day; together, graded, they did.
 *
 * <p>Each reading becomes at most one signal, mild or strong. The verdict depends on how many
 * concordant signals fired and on the {@link Temperament}. Then the athlete's report has the last
 * word, in one direction only: feeling good lifts a yellow day driven by mild signals back to
 * green, and feeling bad never leaves the day green. Feeling good does not lift a red day: an
 * illness can feel fine on its first morning.
 */
public final class ReadinessModel {

    public static final String MODEL = "readiness/two-layer-graded";

    /** Baseline window: as long as available up to 60 days, and no judgement below 14. */
    public static final int BASELINE_MAX_DAYS = 60;
    public static final int BASELINE_MIN_DAYS = 14;
    public static final int TREND_DAYS = 7;

    // Statistical thresholds, on the athlete's own log-transformed variability.
    static final double HRV_MILD_SD = 1.0;
    static final double HRV_STRONG_SD = 2.0;
    static final double HRV_TREND_SD = 0.5;

    // Conventions of practice.
    static final double RESTING_HR_MILD_BPM = 5;
    static final double RESTING_HR_STRONG_BPM = 8;
    static final double RESTING_HR_LOW_NOTE_BPM = -4;
    static final int SLEEP_STRONG_SECONDS = (int) (4.5 * 3600);
    static final int SLEEP_MILD_SECONDS = 6 * 3600;
    static final double SLEEP_SCORE_STRONG = 40;
    static final double SLEEP_SCORE_MILD = 60;
    static final double FORM_STRONG = -30;
    static final double RECENT_LOAD_FACTOR = 4.5;

    private ReadinessModel() {
    }

    public static Readiness assess(ReadinessInput in, Temperament temperament) {
        List<Signal> signals = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        DayRecord today = in.today();
        List<DayRecord> baseline = baseline(in.history());

        hrv(today, baseline, in.history(), signals, missing);
        restingHeartRate(today, baseline, signals, notes, missing);
        sleep(today, signals, missing);
        form(in, signals);
        recentLoad(in, signals);
        Signal feel = feel(in);
        if (feel != null) {
            signals.add(feel);
        }

        signals.sort(Comparator.comparing(Signal::severity).reversed());
        long strong = signals.stream().filter(s -> s.severity() == Severity.STRONG).count();
        long mild = signals.size() - strong;
        Verdict verdict = rule(temperament, strong, mild);

        if (signals.size() == 1 && verdict == Verdict.GREEN) {
            notes.add("A single reading is off (" + signals.getFirst().evidence() + "). On its own it does not change the day.");
        }
        verdict = applyFeel(in.feel(), feel, verdict, strong, signals, notes);

        int score = (int) Math.max(0, Math.min(100, 100 - 12 * mild - 30 * strong));
        return new Readiness(verdict, score, List.copyOf(signals), List.copyOf(notes), List.copyOf(missing), temperament, MODEL);
    }

    static Verdict rule(Temperament t, long strong, long mild) {
        return switch (t) {
            case COMMITTED -> strong >= 2 || (strong >= 1 && mild >= 2) ? Verdict.RED
                    : strong + mild >= 2 ? Verdict.YELLOW : Verdict.GREEN;
            case BALANCED -> strong >= 2 || (strong >= 1 && mild >= 2) ? Verdict.RED
                    : strong >= 1 || mild >= 2 ? Verdict.YELLOW : Verdict.GREEN;
            case CAUTIOUS -> strong >= 1 && strong + mild >= 2 ? Verdict.RED
                    : strong + mild >= 1 ? Verdict.YELLOW : Verdict.GREEN;
        };
    }

    private static Verdict applyFeel(Feel reported, Signal feelSignal, Verdict verdict, long strong,
                                     List<Signal> signals, List<String> notes) {
        if (reported == null) {
            return verdict;
        }
        if (feelSignal != null && verdict == Verdict.GREEN) {
            notes.add("The numbers look fine but you report feeling low. When the two disagree, the coach trusts how you feel.");
            return Verdict.YELLOW;
        }
        boolean objectiveStrong = signals.stream().anyMatch(s -> s.severity() == Severity.STRONG && s.kind() != SignalKind.FEEL);
        if (reported.good() && verdict == Verdict.YELLOW && !objectiveStrong) {
            notes.add("The numbers suggested easing off, but you report feeling good. When the two disagree, the coach trusts how you feel.");
            return Verdict.GREEN;
        }
        if (reported.good() && verdict == Verdict.RED) {
            notes.add("You report feeling good, but several readings point the same way. An illness can feel fine on its first morning, so the day stays easy.");
        }
        return verdict;
    }

    private static List<DayRecord> baseline(List<DayRecord> history) {
        int end = history.size() - 1;
        return history.subList(Math.max(0, end - BASELINE_MAX_DAYS), end);
    }

    private static void hrv(DayRecord today, List<DayRecord> baseline, List<DayRecord> history,
                            List<Signal> signals, List<String> missing) {
        if (today.hrv() == null || today.hrv() <= 0) {
            missing.add("heart rate variability this morning");
            return;
        }
        double[] logs = baseline.stream().filter(d -> d.hrv() != null && d.hrv() > 0).mapToDouble(d -> Math.log(d.hrv())).toArray();
        if (logs.length < BASELINE_MIN_DAYS) {
            missing.add("a baseline for heart rate variability (needs " + BASELINE_MIN_DAYS + " days, has " + logs.length + ")");
            return;
        }
        double mean = mean(logs);
        double sd = sd(logs, mean);
        double z = (Math.log(today.hrv()) - mean) / sd;
        long typical = Math.round(Math.exp(mean));
        if (z < -HRV_STRONG_SD) {
            signals.add(new Signal(SignalKind.HRV_TODAY, Severity.STRONG, String.format(
                    "HRV %.0f ms this morning, %.1f standard deviations below your usual %d ms", today.hrv(), -z, typical), Signal.Registry.STATISTICAL));
        } else if (z < -HRV_MILD_SD) {
            signals.add(new Signal(SignalKind.HRV_TODAY, Severity.MILD, String.format(
                    "HRV %.0f ms this morning, below your usual %d ms", today.hrv(), typical), Signal.Registry.STATISTICAL));
        }
        // The seven mornings before today. Including today would let one collapsed morning count
        // twice, once as the acute signal and again by dragging the weekly mean down.
        List<DayRecord> week = history.subList(Math.max(0, history.size() - 1 - TREND_DAYS), history.size() - 1);
        double[] weekLogs = week.stream().filter(d -> d.hrv() != null && d.hrv() > 0).mapToDouble(d -> Math.log(d.hrv())).toArray();
        if (weekLogs.length == TREND_DAYS && mean(weekLogs) < mean - HRV_TREND_SD * sd) {
            signals.add(new Signal(SignalKind.HRV_TREND, Severity.MILD, String.format(
                    "HRV averaged %.0f ms over the previous seven days, under your normal range", Math.exp(mean(weekLogs))), Signal.Registry.STATISTICAL));
        }
    }

    private static void restingHeartRate(DayRecord today, List<DayRecord> baseline, List<Signal> signals,
                                         List<String> notes, List<String> missing) {
        if (today.restingHeartRate() == null) {
            missing.add("resting heart rate this morning");
            return;
        }
        double[] past = baseline.stream().filter(d -> d.restingHeartRate() != null).mapToDouble(DayRecord::restingHeartRate).toArray();
        if (past.length < BASELINE_MIN_DAYS) {
            missing.add("a baseline for resting heart rate");
            return;
        }
        double usual = median(past);
        double delta = today.restingHeartRate() - usual;
        if (delta >= RESTING_HR_STRONG_BPM) {
            signals.add(new Signal(SignalKind.RESTING_HEART_RATE, Severity.STRONG, String.format(
                    "resting heart rate %d, %.0f above your usual %.0f", today.restingHeartRate(), delta, usual), Signal.Registry.CONVENTION));
        } else if (delta >= RESTING_HR_MILD_BPM) {
            signals.add(new Signal(SignalKind.RESTING_HEART_RATE, Severity.MILD, String.format(
                    "resting heart rate %d, %.0f above your usual %.0f", today.restingHeartRate(), delta, usual), Signal.Registry.CONVENTION));
        } else if (delta <= RESTING_HR_LOW_NOTE_BPM) {
            notes.add(String.format("Resting heart rate %d is unusually low against your usual %.0f. Coaches sometimes see this before an illness; on its own it means nothing.",
                    today.restingHeartRate(), usual));
        }
    }

    private static void sleep(DayRecord today, List<Signal> signals, List<String> missing) {
        Integer secs = today.sleepSeconds();
        Double score = today.sleepScore();
        if (secs == null && score == null) {
            missing.add("last night's sleep");
            return;
        }
        String what = describeSleep(secs, score);
        if ((secs != null && secs < SLEEP_STRONG_SECONDS) || (score != null && score < SLEEP_SCORE_STRONG)) {
            signals.add(new Signal(SignalKind.SLEEP, Severity.STRONG, "a very poor night: " + what, Signal.Registry.CONVENTION));
        } else if ((secs != null && secs < SLEEP_MILD_SECONDS) || (score != null && score < SLEEP_SCORE_MILD)) {
            signals.add(new Signal(SignalKind.SLEEP, Severity.MILD, "a short or poor night: " + what, Signal.Registry.CONVENTION));
        }
    }

    private static void form(ReadinessInput in, List<Signal> signals) {
        if (in.fitness() == null || in.fatigue() == null) {
            return;
        }
        double form = in.fitness() - in.fatigue();
        if (form < FORM_STRONG) {
            signals.add(new Signal(SignalKind.FORM, Severity.STRONG, String.format(
                    "form at %.0f: fatigue is far above fitness", form), Signal.Registry.CONVENTION));
        }
    }

    private static void recentLoad(ReadinessInput in, List<Signal> signals) {
        if (in.fitness() == null || in.fitness() <= 0) {
            return;
        }
        if (in.loadLast3Days() > RECENT_LOAD_FACTOR * in.fitness()) {
            signals.add(new Signal(SignalKind.RECENT_LOAD, Severity.MILD, String.format(
                    "%.0f of load over the last three days, a heavy block for your fitness of %.0f", in.loadLast3Days(), in.fitness()),
                    Signal.Registry.CONVENTION));
        }
    }

    private static Signal feel(ReadinessInput in) {
        Feel f = in.feel();
        if (f == null) {
            return null;
        }
        boolean lowForDays = in.recentFeels().size() >= 2
                && in.recentFeels().subList(in.recentFeels().size() - 2, in.recentFeels().size()).stream().allMatch(p -> p.lowDimensions() >= 2)
                && f.lowDimensions() >= 2;
        if (f.sleepAndMoodBothLow() || f.lowDimensions() >= 3 || lowForDays) {
            String why = f.sleepAndMoodBothLow() ? "you report both poor sleep and low mood, which reads as exhaustion"
                    : lowForDays ? "you have reported feeling low for three days running"
                    : "you report feeling low on " + f.lowDimensions() + " of five counts";
            return new Signal(SignalKind.FEEL, Severity.STRONG, why, Signal.Registry.CONVENTION);
        }
        if (f.lowDimensions() == 2) {
            return new Signal(SignalKind.FEEL, Severity.MILD, "you report feeling low on two of five counts", Signal.Registry.CONVENTION);
        }
        return null;
    }

    private static String describeSleep(Integer secs, Double score) {
        StringBuilder b = new StringBuilder();
        if (secs != null) {
            b.append(String.format("%dh%02d of sleep", secs / 3600, (secs % 3600) / 60));
        }
        if (score != null) {
            b.append(b.isEmpty() ? "" : ", ").append(String.format("sleep score %.0f", score));
        }
        return b.toString();
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    private static double sd(double[] v, double mean) {
        double s = 0;
        for (double x : v) {
            s += (x - mean) * (x - mean);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        java.util.Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2;
    }
}
