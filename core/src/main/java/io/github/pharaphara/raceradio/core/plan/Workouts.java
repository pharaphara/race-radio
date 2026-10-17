package io.github.pharaphara.raceradio.core.plan;

import io.github.pharaphara.raceradio.core.workout.Length;
import io.github.pharaphara.raceradio.core.workout.Section;
import io.github.pharaphara.raceradio.core.workout.Step;
import io.github.pharaphara.raceradio.core.workout.Target;
import io.github.pharaphara.raceradio.core.workout.Workout;

import java.util.ArrayList;
import java.util.List;

/**
 * The workout library, written from the athlete's thresholds. Intensities follow the zone
 * conventions of the knowledge base: endurance up to about three quarters of threshold power, easy
 * running 50 to 75 s/km slower than threshold pace, swim paces as offsets from critical swim speed.
 * Titles and cues carry no digit, so the calendar never reads them as instructions.
 */
public final class Workouts {

    private Workouts() {
    }

    private static Step time(int minutes, Target t) {
        return Step.of(new Length.Time(minutes * 60), t);
    }

    private static Step seconds(int s, Target t, String cue) {
        return new Step(new Length.Time(s), t, null, cue);
    }

    private static Target.Power pct(int ftp, double low, double high) {
        return new Target.Power((int) Math.round(ftp * low), (int) Math.round(ftp * high));
    }

    private static Target.RunPace pace(int threshold, int fastOffset, int slowOffset) {
        return new Target.RunPace(threshold + fastOffset, threshold + slowOffset);
    }

    private static Target.SwimPace swim(int css, int fastOffset, int slowOffset) {
        return new Target.SwimPace(css + fastOffset, css + slowOffset);
    }

    public static Workout bikeEndurance(int ftp, int minutes) {
        int main = Math.max(10, minutes - 15);
        return new Workout("Ride", "Vélo endurance · " + minutes + " min", List.of(
                new Section("Échauffement", 1, List.of(time(10, pct(ftp, 0.50, 0.60)))),
                new Section("Corps de séance", 1, List.of(time(main, pct(ftp, 0.60, 0.72)))),
                new Section("Retour au calme", 1, List.of(time(5, pct(ftp, 0.45, 0.55))))));
    }

    /** Base phase: sweet spot, the one hard bike session of the week. */
    public static Workout bikeSweetSpot(int ftp, int minutes) {
        int reps = Math.clamp((minutes - 25) / 17, 1, 3);
        return new Workout("Ride", "Vélo sweet spot · " + reps + " × 12 min", List.of(
                new Section("Échauffement", 1, List.of(time(15, pct(ftp, 0.50, 0.70)))),
                new Section("Corps de séance", reps, List.of(time(12, pct(ftp, 0.88, 0.93)), time(5, pct(ftp, 0.50, 0.58)))),
                new Section("Retour au calme", 1, List.of(time(10, pct(ftp, 0.45, 0.55))))));
    }

    /** Specific phase: threshold. */
    public static Workout bikeThreshold(int ftp, int minutes) {
        int reps = Math.clamp((minutes - 25) / 12, 2, 4);
        return new Workout("Ride", "Vélo seuil · " + reps + " × 8 min", List.of(
                new Section("Échauffement", 1, List.of(time(15, pct(ftp, 0.50, 0.70)))),
                new Section("Corps de séance", reps, List.of(time(8, pct(ftp, 0.95, 1.02)), time(4, pct(ftp, 0.50, 0.58)))),
                new Section("Retour au calme", 1, List.of(time(10, pct(ftp, 0.45, 0.55))))));
    }

    public static Workout runEasy(int threshold, int minutes, boolean strides) {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Footing", 1, List.of(time(Math.max(15, minutes - (strides ? 3 : 0)), pace(threshold, 55, 75)))));
        if (strides) {
            sections.add(new Section("Accélérations", 4, List.of(seconds(20, pace(threshold, -25, -15), "relâché"),
                    seconds(25, pace(threshold, 80, 110), "marche ou trot"))));
        }
        return new Workout("Run", "Course facile · " + minutes + " min", sections);
    }

    public static Workout runThreshold(int threshold, int minutes) {
        int reps = Math.clamp((minutes - 25) / 10, 2, 4);
        return new Workout("Run", "Course seuil · " + reps + " × 8 min", List.of(
                new Section("Échauffement", 1, List.of(time(15, pace(threshold, 55, 75)))),
                new Section("Corps de séance", reps, List.of(time(8, pace(threshold, -3, 5)), time(2, pace(threshold, 70, 100)))),
                new Section("Retour au calme", 1, List.of(time(10, pace(threshold, 60, 80))))));
    }

    /** Bike then run, the transition the race asks for. Two workouts on the same day. */
    public static List<Workout> brick(int ftp, int threshold, int minutes) {
        int bike = Math.max(30, minutes - 20);
        Workout b = new Workout("Ride", "Enchaînement · vélo " + bike + " min", List.of(
                new Section("Échauffement", 1, List.of(time(10, pct(ftp, 0.50, 0.65)))),
                new Section("Corps de séance", 1, List.of(time(bike - 10, pct(ftp, 0.76, 0.85))))));
        Workout r = new Workout("Run", "Enchaînement · course 15 min", List.of(
                new Section("Transition", 1, List.of(time(15, pace(threshold, 10, 25))))));
        return List.of(b, r);
    }

    /** Technique before volume: drills, then a few controlled hundreds. */
    public static Workout swimTechnique(int css) {
        return swimTechnique(css, false);
    }

    /** {@code light}: the recovery-week version, fewer drills and hundreds. */
    public static Workout swimTechnique(int css, boolean light) {
        return new Workout("Swim", light ? "Natation technique légère" : "Natation technique", List.of(
                new Section("Échauffement", 1, List.of(new Step(new Length.Distance(light ? 200 : 300), swim(css, 15, 20), null, "souple"))),
                new Section("Éducatifs", light ? 4 : 6, List.of(new Step(new Length.Distance(50), swim(css, 12, 18), null, "rattrapé ou poings fermés"))),
                new Section("Corps de séance", light ? 2 : 4, List.of(new Step(new Length.Distance(100), swim(css, 4, 8), null, "allonger le mouvement"))),
                new Section("Retour au calme", 1, List.of(new Step(new Length.Distance(200), swim(css, 15, 25), null, "pull buoy")))));
    }

    public static Workout recovery(String sport, Thresholds t, int minutes) {
        if ("Ride".equals(sport) && t.thresholdPower() != null) {
            return new Workout("Ride", "Vélo récupération · " + minutes + " min", List.of(
                    new Section("Récupération", 1, List.of(time(minutes, pct(t.thresholdPower(), 0.40, 0.55))))));
        }
        return new Workout("Run", "Footing récupération · " + minutes + " min", List.of(
                new Section("Récupération", 1, List.of(time(minutes, pace(t.thresholdPace(), 75, 100))))));
    }
}
