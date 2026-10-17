package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.charts.Charts;
import io.github.pharaphara.raceradio.charts.Png;
import io.github.pharaphara.raceradio.charts.Theme;
import io.github.pharaphara.raceradio.core.series.Samples;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The images sent with a review: effort and heart rate over time with the work blocks washed in,
 * then time in zones. Effort is drawn in the unit the athlete reads: watts on the bike, pace per
 * kilometre on foot, pace per 100 m in the water, faster at the top.
 */
public final class SessionCharts {

    /** Each entity keeps its colour on every chart: effort blue, heart rate orange. */
    static final int EFFORT = 0;
    static final int HEART = 1;

    private static final Map<String, String> ZONES_FR = Map.ofEntries(
            Map.entry("Recovery", "Récupération"), Map.entry("Aerobic", "Endurance"), Map.entry("Tempo", "Tempo"),
            Map.entry("Sub-threshold", "Sous le seuil"), Map.entry("Threshold", "Seuil"), Map.entry("VO2max", "VO2max"),
            Map.entry("Anaerobic", "Anaérobie"), Map.entry("Easy", "Facile"), Map.entry("Steady", "Endurance active"),
            Map.entry("Interval", "Intervalle"), Map.entry("Repetition", "Répétition"));

    private SessionCharts() {
    }

    public static List<byte[]> render(SessionFacts f, Samples effort, Samples heartRate) {
        List<byte[]> images = new ArrayList<>();
        if (!f.enoughData() || effort == null) {
            return images;
        }
        int n = effort.length();
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
        }
        List<Charts.Panel> panels = new ArrayList<>();
        panels.add(effortPanel(f, effort));
        if (heartRate != null) {
            double[] hr = heartRate.toArray();
            for (int i = 0; i < hr.length; i++) {
                if (!(hr[i] > 0)) {
                    hr[i] = Double.NaN; // a zero is a strap that lost contact, not a reading
                }
            }
            panels.add(Charts.Panel.of("Fréquence cardiaque (bpm)", List.of(new Charts.Series("FC", hr, HEART)),
                    y -> String.valueOf(Math.round(y))).forSession().fullRange());
        }
        List<Charts.Band> bands = f.work().stream()
                .map(b -> new Charts.Band(b.startSecond(), b.startSecond() + b.seconds())).toList();
        String subtitle = (f.start() == null ? "" : f.start().format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRANCE)) + " · ")
                + (f.work().isEmpty() ? "pas de bloc de travail"
                : ("DEVICE_LAPS".equals(f.structureSource()) ? "blocs repris des tours de ta montre" : "blocs détectés"));
        String svg = Charts.panels(title(f), subtitle, x, s -> (int) (s / 60) + " min", 600, panels, bands, Theme.DARK);
        images.add(Png.of(svg, Charts.WIDTH, 2));

        if (!f.secondsInZones().isEmpty()) {
            List<String> labels = new ArrayList<>();
            double[] values = new double[f.secondsInZones().size()];
            int i = 0;
            for (Map.Entry<String, Integer> e : f.secondsInZones().entrySet()) {
                String[] parts = e.getKey().split(" ", 2);
                labels.add(parts[0] + " " + ZONES_FR.getOrDefault(parts.length > 1 ? parts[1] : "", parts.length > 1 ? parts[1] : ""));
                values[i++] = e.getValue();
            }
            String zones = Charts.bars("Temps par zone", "W".equals(f.unit()) ? "zones de puissance" : "zones d'allure",
                    labels, values, s -> s > 0 && s < 60 ? "< 1 min" : Math.round(s / 60) + " min", Theme.DARK);
            images.add(Png.of(zones, Charts.WIDTH, 2));
        }
        return images;
    }

    private static Charts.Panel effortPanel(SessionFacts f, Samples effort) {
        if ("W".equals(f.unit())) {
            return Charts.Panel.of("Puissance (W)", List.of(new Charts.Series("puissance", effort.toArray(), EFFORT)),
                    y -> String.valueOf(Math.round(y))).forSession();
        }
        boolean swim = EffortSignal.SWIM.contains(f.sport());
        double per = swim ? 100 : 1000;
        double[] pace = effort.toArray();
        for (int i = 0; i < pace.length; i++) {
            pace[i] = pace[i] > 0.3 ? per / pace[i] : Double.NaN; // standing still has no pace
        }
        return Charts.Panel.of(swim ? "Allure (min/100 m)" : "Allure (min/km)", List.of(new Charts.Series("allure", pace, EFFORT)),
                y -> (int) y / 60 + ":" + String.format("%02d", Math.round(y) % 60)).inverted(swim ? 10 : 30).forSession();
    }

    private static final int MAX_TITLE_GROUPS = 3;

    private static String title(SessionFacts f) {
        String sport = EffortSignal.BIKE.contains(f.sport()) ? "Vélo" : EffortSignal.RUN.contains(f.sport()) ? "Course"
                : EffortSignal.SWIM.contains(f.sport()) ? "Natation" : f.sport();
        if (f.repetitions().isEmpty()) {
            return sport;
        }
        // The title is one line on a phone: past a few groups it names the count, and the chart shows the rest.
        List<String> groups = f.repetitions().stream().map(r -> r.startsWith("1 × ") ? r.substring(4) : r).toList();
        return groups.size() <= MAX_TITLE_GROUPS ? sport + " · " + String.join(", ", groups)
                : sport + " · " + f.work().size() + " blocs de travail";
    }
}
