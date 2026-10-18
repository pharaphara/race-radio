package io.github.pharaphara.raceradio.bot.archive;

import io.github.pharaphara.raceradio.core.metrics.Trend.Aggregate;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleFunction;

/**
 * Every figure {@code /tendance} can follow, and where it lives in the local archive.
 *
 * <p>Most are fields of the activity summary as Intervals.icu computed them, read with
 * {@code json_extract}; a few come from {@code computed_metric}; the daily ones from the wellness
 * archive. Each carries the filter that keeps only comparable sessions: an efficiency figure taken
 * on an interval session measures the workout, not the athlete.
 */
public final class MetricCatalog {

    public enum Group {
        BIKE("vélo", "'Ride','VirtualRide','GravelRide','MountainBikeRide'"),
        RUN("course", "'Run','TrailRun','VirtualRun','Treadmill'"),
        SWIM("natation", "'Swim','OpenWaterSwim'"),
        DAILY("au quotidien", null);

        final String label;
        final String types;

        Group(String label, String types) {
            this.label = label;
            this.types = types;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param sql           SQL expression giving the value, over {@code a} (activity) or {@code w} (wellness day)
     * @param filter        SQL condition keeping comparable sessions, or "1"
     * @param comparable    what the filter keeps, said to the athlete
     * @param higherBetter  true, false, or null when neither direction is better (stride, weight)
     */
    public record Metric(String key, Group group, String label, String sql, String filter, String comparable,
                         Aggregate aggregate, Boolean higherBetter, DoubleFunction<String> format, List<String> words) {
    }

    private static String s(String field) {
        return "json_extract(a.json, '$." + field + "')";
    }

    private static String c(String key) {
        return "(SELECT value FROM computed_metric c WHERE c.activity_id = a.id AND c.metric = '" + key + "')";
    }

    private static String w(String field) {
        return "json_extract(w.json, '$." + field + "')";
    }

    private static final String STEADY_BIKE = s("icu_intensity") + " <= 80 AND " + s("moving_time") + " >= 1800";
    private static final String EASY_RUN = s("icu_intensity") + " <= 85 AND " + s("moving_time") + " >= 1800 AND "
            + s("total_elevation_gain") + " <= 15 * " + s("distance") + " / 1000.0";

    private static DoubleFunction<String> num(int decimals, String unit) {
        return v -> String.format(Locale.FRANCE, "%." + decimals + "f", v) + (unit.isEmpty() ? "" : " " + unit);
    }

    private static DoubleFunction<String> clock(String unit) {
        return v -> {
            long t = Math.round(v);
            return (t >= 3600 ? t / 3600 + "h" + String.format("%02d", t % 3600 / 60) + ":" : t / 60 + ":")
                    + String.format("%02d", t % 60) + unit;
        };
    }

    public static final List<Metric> ALL = List.of(
            new Metric("velo_efficacite", Group.BIKE, "efficacité aérobie, puissance pondérée par battement", s("icu_efficiency_factor"),
                    STEADY_BIKE, "sorties d'endurance (intensité 80 % ou moins, 30 min et plus)", Aggregate.MEDIAN, true,
                    num(2, "W/bpm"), List.of("efficacite", "ef", "w/bpm", "watt/bpm", "watts/bpm", "w par battement")),
            new Metric("velo_derive", Group.BIKE, "dérive cardiaque à vélo", s("decoupling"),
                    STEADY_BIKE + " AND " + s("moving_time") + " >= 3600", "sorties d'endurance d'une heure et plus",
                    Aggregate.MEDIAN, false, num(1, "%"), List.of("derive", "decouplage", "decoupling")),
            new Metric("velo_fc_endurance", Group.BIKE, "FC à puissance d'endurance (65 à 75 % de la FTP)", c("hr_at_endurance_power"),
                    "1", "sorties avec au moins 10 min dans cette plage, hors échauffement", Aggregate.MEDIAN, false,
                    num(0, "bpm"), List.of("fc endurance", "fc a puissance", "fc a puissance donnee", "bpm a puissance")),
            new Metric("velo_ftp", Group.BIKE, "FTP estimée par Intervals.icu", s("icu_rolling_ftp"),
                    "1", "toutes les sorties avec puissance", Aggregate.MEDIAN, true, num(0, "W"), List.of("ftp", "seuil velo")),
            new Metric("velo_5s", Group.BIKE, "meilleure puissance sur 5 s", c("best_power_5s"), "1", "toutes les sorties avec puissance",
                    Aggregate.MAX, true, num(0, "W"), List.of("5s", "sprint")),
            new Metric("velo_1min", Group.BIKE, "meilleure puissance sur 1 min", c("best_power_1min"), "1", "toutes les sorties avec puissance",
                    Aggregate.MAX, true, num(0, "W"), List.of("1min", "1 min")),
            new Metric("velo_5min", Group.BIKE, "meilleure puissance sur 5 min", c("best_power_5min"), "1", "toutes les sorties avec puissance",
                    Aggregate.MAX, true, num(0, "W"), List.of("5min", "5 min", "pma", "vo2")),
            new Metric("velo_20min", Group.BIKE, "meilleure puissance sur 20 min", c("best_power_20min"), "1", "toutes les sorties avec puissance",
                    Aggregate.MAX, true, num(0, "W"), List.of("20min", "20 min")),
            new Metric("velo_60min", Group.BIKE, "meilleure puissance sur 60 min", c("best_power_60min"), "1", "toutes les sorties avec puissance",
                    Aggregate.MAX, true, num(0, "W"), List.of("60min", "60 min", "1h", "heure")),
            new Metric("velo_cadence", Group.BIKE, "cadence moyenne à vélo", s("average_cadence"), STEADY_BIKE,
                    "sorties d'endurance", Aggregate.MEDIAN, null, num(0, "rpm"), List.of("cadence velo", "rpm")),
            new Metric("course_efficacite", Group.RUN, "mètres parcourus par battement de cœur",
                    s("average_speed") + " * 60.0 / " + s("average_heartrate"), EASY_RUN,
                    "footings (intensité 85 % ou moins, 30 min et plus, 15 m de dénivelé par km au plus)", Aggregate.MEDIAN, true,
                    num(2, "m/battement"), List.of("efficacite course", "allure/bpm", "vitesse/bpm", "km/h/bpm", "m/bpm", "allure par battement")),
            new Metric("course_derive", Group.RUN, "dérive cardiaque en course", c("run_decoupling_percent"),
                    EASY_RUN + " AND " + s("moving_time") + " >= 2400", "footings de 40 min et plus", Aggregate.MEDIAN, false,
                    num(1, "%"), List.of("derive course", "decouplage course")),
            new Metric("course_1km", Group.RUN, "meilleur 1 km", c("best_time_1km"), "1", "toutes les courses",
                    Aggregate.MIN, false, clock(""), List.of("1km", "1 km", "kilometre")),
            new Metric("course_5km", Group.RUN, "meilleur 5 km", c("best_time_5km"), "1", "toutes les courses",
                    Aggregate.MIN, false, clock(""), List.of("5km", "5 km")),
            new Metric("course_foulee", Group.RUN, "longueur de foulée à allure facile", c("stride_at_easy_pace"), "1",
                    "courses avec au moins 5 min à allure facile (72 à 90 % de la vitesse seuil)", Aggregate.MEDIAN, null,
                    num(2, "m"), List.of("foulee", "longueur de foulee", "stride")),
            new Metric("course_cadence", Group.RUN, "cadence à allure facile", c("cadence_at_easy_pace"), "1",
                    "courses avec au moins 5 min à allure facile", Aggregate.MEDIAN, true, num(0, "pas/min"),
                    List.of("cadence course", "cadence", "pas/min", "spm")),
            new Metric("course_ratio_vertical", Group.RUN, "ratio vertical", s("average_vertical_ratio"), EASY_RUN,
                    "footings", Aggregate.MEDIAN, false, num(1, "%"), List.of("ratio vertical", "oscillation")),
            new Metric("nage_distance_par_mouvement", Group.SWIM, "distance par mouvement de bras", c("distance_per_stroke"), "1",
                    "toutes les nages avec cadence", Aggregate.MEDIAN, true, num(2, "m"),
                    List.of("distance par mouvement", "dps", "glisse", "mouvement de bras")),
            new Metric("nage_allure", Group.SWIM, "allure moyenne de nage", "100.0 / " + s("average_speed"),
                    s("average_speed") + " > 0", "toutes les nages", Aggregate.MEDIAN, false, clock("/100 m"),
                    List.of("allure nage", "allure natation", "nage", "natation")),
            new Metric("vfc", Group.DAILY, "variabilité cardiaque (VFC) au réveil", w("hrv"), "1", "chaque matin mesuré",
                    Aggregate.MEDIAN, true, num(0, "ms"), List.of("vfc", "hrv", "variabilite")),
            new Metric("fc_repos", Group.DAILY, "FC de repos", w("restingHR"), "1", "chaque matin mesuré",
                    Aggregate.MEDIAN, false, num(0, "bpm"), List.of("fc repos", "fc de repos", "repos")),
            new Metric("sommeil", Group.DAILY, "durée de sommeil", w("sleepSecs") + " / 3600.0", "1", "chaque nuit mesurée",
                    Aggregate.MEDIAN, true, num(1, "h"), List.of("sommeil", "dodo")),
            new Metric("poids", Group.DAILY, "poids", w("weight"), "1", "chaque pesée", Aggregate.MEDIAN, null,
                    num(1, "kg"), List.of("poids", "masse")),
            new Metric("vo2max", Group.DAILY, "VO2max estimée par la montre", w("vo2max"), "1", "chaque estimation",
                    Aggregate.MEDIAN, true, num(0, ""), List.of("vo2max", "vo2 max")),
            new Metric("forme_de_fond", Group.DAILY, "forme de fond (fitness)", w("ctl"), "1", "chaque jour",
                    Aggregate.MEDIAN, true, num(0, ""), List.of("forme de fond", "fitness", "forme")));

    private MetricCatalog() {
    }

    /**
     * The metric the athlete's words name, preferring the longest match, then one of the sport they
     * mention. Empty when nothing matches.
     */
    public static Optional<Metric> find(String words) {
        String q = plain(words).strip();
        for (Metric m : ALL) {
            if (m.key().equals(q)) {
                return Optional.of(m);
            }
        }
        java.util.Set<String> tokens = java.util.Set.of(q.split("[\\s,;]+"));
        Group sport = tokens.contains("velo") ? Group.BIKE
                : tokens.contains("course") || tokens.contains("cap") || tokens.contains("footing") ? Group.RUN
                : tokens.contains("nage") || tokens.contains("natation") ? Group.SWIM : null;
        Metric best = null;
        int bestScore = 0;
        for (Metric m : ALL) {
            // A sport named restricts the search to it: "course efficacité" never lands on the bike.
            if (sport != null && m.group() != sport && m.group() != Group.DAILY) {
                continue;
            }
            for (String w : m.words()) {
                // Every word of the alias, in any order: "course efficacité" matches "efficacite course".
                if (!tokens.containsAll(java.util.List.of(w.split(" ")))) {
                    continue;
                }
                int score = w.length() * 2 + (m.group() == sport ? 1 : 0);
                if (score > bestScore) {
                    best = m;
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    static String plain(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
