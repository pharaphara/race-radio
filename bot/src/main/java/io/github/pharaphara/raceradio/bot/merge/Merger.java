package io.github.pharaphara.raceradio.bot.merge;

import io.github.pharaphara.raceradio.core.merge.ActivityMerge;
import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /fusionner: two activities of one session, cut in two by mistake, become one again. The earlier
 * one receives the joined streams and Intervals.icu recomputes it; the later one is then deleted.
 * Because that deletion removes a real activity that carries no ownership mark, nothing happens
 * before the athlete has seen a preview and confirmed it.
 */
public final class Merger {

    /** Beyond this gap, two activities are two sessions, not one cut in two. */
    static final Duration MAX_GAP = Duration.ofHours(3);

    public record Preview(ActivitySummary first, ActivitySummary second, String text, boolean possible) {
    }

    private final IntervalsClient intervals;
    private final IntervalsWriter writer;

    public Merger(IntervalsClient intervals, IntervalsWriter writer) {
        this.intervals = intervals;
        this.writer = writer;
    }

    public Preview preview(String idA, String idB) {
        ActivitySummary a = intervals.activity(idA);
        ActivitySummary b = intervals.activity(idB);
        ActivitySummary first = a.start().isAfter(b.start()) ? b : a;
        ActivitySummary second = first == a ? b : a;
        if (!family(first.type()).equals(family(second.type()))) {
            return new Preview(first, second, "Refusé : ce ne sont pas le même sport (" + first.type() + ", " + second.type() + ").", false);
        }
        if (!first.start().toLocalDate().equals(second.start().toLocalDate())) {
            return new Preview(first, second, "Refusé : elles ne sont pas du même jour.", false);
        }
        long gap = Duration.between(first.start(), second.start()).toSeconds() - (first.movingSeconds() == null ? 0 : first.movingSeconds());
        if (gap > MAX_GAP.toSeconds()) {
            return new Preview(first, second, "Refusé : plus de trois heures les séparent, ce sont deux séances.", false);
        }
        String text = "Fusion proposée :\n• " + line(first) + "\n• " + line(second)
                + "\nLa première reçoit les données des deux et Intervals.icu la recalcule ; la seconde est ensuite supprimée."
                + (writer.mode() == IntervalsWriter.Mode.DRY_RUN ? "\n(Mode sec : rien ne sera écrit.)" : "");
        return new Preview(first, second, text, true);
    }

    /** Runs a merge the athlete confirmed. */
    public String merge(String idA, String idB) {
        Preview p = preview(idA, idB);
        if (!p.possible()) {
            return p.text();
        }
        long between = Duration.between(p.first().start(), p.second().start()).toSeconds();
        Map<String, double[]> joined = ActivityMerge.concat(columns(intervals.streams(p.first().id())),
                columns(intervals.streams(p.second().id())), between);
        writer.replaceStreams(p.first().id(), joined);
        writer.deleteMergedActivity(p.second().id(), p.first().id());
        return writer.mode() == IntervalsWriter.Mode.DRY_RUN
                ? "Mode sec : la fusion a été préparée mais rien n'a été écrit."
                : "Fusionnées : « " + p.first().name() + " » contient maintenant les deux parties.";
    }

    private static Map<String, double[]> columns(ActivityStreams s) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String name : s.names()) {
            out.put(name, s.get(name).map(Samples::toArray).orElseThrow());
        }
        return out;
    }

    private static String line(ActivitySummary a) {
        int secs = a.movingSeconds() == null ? 0 : a.movingSeconds();
        return a.start().toLocalTime().withNano(0) + " " + a.name() + ", " + secs / 60 + " min"
                + (a.distanceMetres() == null ? "" : String.format(", %.1f km", a.distanceMetres() / 1000));
    }

    static String family(String type) {
        if (type == null) {
            return "";
        }
        if (type.contains("Ride")) {
            return "Ride";
        }
        if (type.contains("Run")) {
            return "Run";
        }
        if (type.contains("Swim")) {
            return "Swim";
        }
        return type;
    }
}
