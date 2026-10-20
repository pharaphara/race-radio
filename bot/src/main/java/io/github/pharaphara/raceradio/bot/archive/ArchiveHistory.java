package io.github.pharaphara.raceradio.bot.archive;

import io.github.pharaphara.raceradio.bot.chat.History;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The conversation's view of the past, answered from the local archive only. */
public final class ArchiveHistory implements History {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE);
    private static final String WELLNESS = "SELECT day, json_extract(json, '$.hrv') AS hrv, json_extract(json, '$.restingHR') AS rhr, "
            + "json_extract(json, '$.sleepSecs') AS sleep, json_extract(json, '$.sleepScore') AS score, "
            + "json_extract(json, '$.weight') AS weight, json_extract(json, '$.ctl') AS fitness, json_extract(json, '$.atl') AS fatigue "
            + "FROM archive_wellness WHERE athlete_id = ? AND ";

    private final Archive archive;
    private final Trends trends;

    public ArchiveHistory(Archive archive, Trends trends) {
        this.archive = archive;
        this.trends = trends;
    }

    @Override
    public List<String> recentDays(int days) {
        String from = archive.today().minusDays(days).toString();
        return archive.jdbc().queryForList(WELLNESS + "day >= ? ORDER BY day", archive.athlete(), from).stream()
                .map(ArchiveHistory::morning).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public List<String> recentSessions(int days) {
        return archive.recent(days);
    }

    @Override
    public Optional<String> day(LocalDate day) {
        List<String> lines = new ArrayList<>();
        archive.jdbc().queryForList(WELLNESS + "day = ?", archive.athlete(), day.toString()).stream()
                .map(ArchiveHistory::morning).filter(s -> !s.isEmpty())
                .forEach(s -> lines.add("Matin : " + s.substring(11)));
        archive.jdbc().queryForList("SELECT json_extract(json, '$.name') AS name, type, json_extract(json, '$.moving_time') AS t, "
                        + "json_extract(json, '$.icu_training_load') AS load, json_extract(json, '$.average_heartrate') AS hr, "
                        + "json_extract(json, '$.icu_weighted_avg_watts') AS wap, json_extract(json, '$.distance') AS dist "
                        + "FROM archive_activity WHERE athlete_id = ? AND day = ? ORDER BY json_extract(json, '$.start_date_local')",
                        archive.athlete(), day.toString())
                .forEach(r -> lines.add("Séance : « " + r.get("name") + " » " + r.get("type")
                        + (r.get("t") instanceof Number n ? ", " + n.intValue() / 60 + " min" : "")
                        + (r.get("dist") instanceof Number d && d.doubleValue() > 0 ? String.format(Locale.FRANCE, ", %.1f km", d.doubleValue() / 1000) : "")
                        + (r.get("wap") instanceof Number w ? ", " + w.intValue() + " W pondérés" : "")
                        + (r.get("hr") instanceof Number h ? ", FC moyenne " + h.intValue() : "")
                        + (r.get("load") instanceof Number l ? ", charge " + l.intValue() : "")));
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String head = DAY.format(day);
        return Optional.of(Character.toUpperCase(head.charAt(0)) + head.substring(1) + "\n" + String.join("\n", lines));
    }

    @Override
    public Optional<String> activity(LocalDate day) {
        List<Map<String, String>> sessions = sessions(day);
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, String> s : sessions) {
            if (!b.isEmpty()) {
                b.append("\n\n");
            }
            s.forEach((k, v) -> {
                if (!k.equals("Sport")) {
                    b.append(k.equals("Séance") ? v : k + " : " + v).append('\n');
                }
            });
        }
        return Optional.of(b.toString().strip());
    }

    @Override
    public Optional<String> compare(LocalDate one, LocalDate two) {
        List<Map<String, String>> a = sessions(one);
        List<Map<String, String>> b = sessions(two);
        if (a.isEmpty() || b.isEmpty()) {
            return Optional.empty();
        }
        // The longest session of each day, of the same sport when there is a choice.
        Map<String, String> first = a.stream().max(java.util.Comparator.comparingInt(m -> m.size())).orElseThrow();
        Map<String, String> second = b.stream().filter(m -> m.get("Sport").equals(first.get("Sport"))).findFirst()
                .orElse(b.stream().max(java.util.Comparator.comparingInt(m -> m.size())).orElseThrow());
        StringBuilder out = new StringBuilder(DAY.format(one) + " → " + DAY.format(two) + "\n");
        out.append(first.get("Séance")).append(" → ").append(second.get("Séance")).append('\n');
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(first.keySet());
        keys.addAll(second.keySet());
        keys.remove("Séance");
        keys.remove("Sport");
        for (String k : keys) {
            out.append(k).append(" : ").append(first.getOrDefault(k, "–")).append(" → ").append(second.getOrDefault(k, "–")).append('\n');
        }
        return Optional.of(out.toString().strip());
    }

    @Override
    public List<String> plannedDetail(int days) {
        LocalDate today = archive.today();
        return archive.jdbc().queryForList("SELECT day, json_extract(json, '$.category') AS cat, json_extract(json, '$.type') AS type, "
                        + "json_extract(json, '$.name') AS name, json_extract(json, '$.description') AS description "
                        + "FROM archive_event WHERE athlete_id = ? AND day >= ? AND day < ? AND json_extract(json, '$.category') = 'WORKOUT' "
                        + "ORDER BY day",
                archive.athlete(), today.toString(), today.plusDays(days).toString()).stream()
                .map(r -> r.get("day") + " " + r.get("type") + " « " + r.get("name") + " »\n"
                        + (r.get("description") instanceof String d ? (d.length() > 1_200 ? d.substring(0, 1_200) : d) : ""))
                .toList();
    }

    /** Each session of a day as label to value, in reading order. */
    private List<Map<String, String>> sessions(LocalDate day) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> r : archive.jdbc().queryForList("SELECT id, json FROM archive_activity WHERE athlete_id = ? AND day = ? "
                + "ORDER BY json_extract(json, '$.start_date_local')", archive.athlete(), day.toString())) {
            tools.jackson.databind.JsonNode a = JSON.readTree((String) r.get("json"));
            String type = a.path("type").asString();
            boolean run = type.contains("Run");
            boolean swim = type.contains("Swim");
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("Séance", "« " + a.path("name").asString() + " » (" + type + ")");
            m.put("Sport", type);
            put(m, "Durée en mouvement", a.path("moving_time"), v -> clock((int) v));
            put(m, "Distance", a.path("distance"), v -> String.format(Locale.FRANCE, "%.1f km", v / 1000));
            put(m, run ? "Allure en mouvement" : swim ? "Allure en mouvement" : "Vitesse en mouvement", a.path("average_speed"),
                    v -> run ? pace(1000 / v) + "/km" : swim ? pace(100 / v) + "/100 m" : String.format(Locale.FRANCE, "%.1f km/h", v * 3.6));
            put(m, "Puissance moyenne", a.path("icu_average_watts"), v -> Math.round(v) + " W");
            put(m, "Puissance pondérée", a.path("icu_weighted_avg_watts"), v -> Math.round(v) + " W");
            put(m, "FC moyenne", a.path("average_heartrate"), v -> String.valueOf(Math.round(v)));
            put(m, "FC max", a.path("max_heartrate"), v -> String.valueOf(Math.round(v)));
            put(m, "Cadence", a.path("average_cadence"), v -> run ? Math.round(2 * v) + " pas/min" : Math.round(v) + " rpm");
            put(m, "Dénivelé", a.path("total_elevation_gain"), v -> Math.round(v) + " m");
            put(m, "Charge", a.path("icu_training_load"), v -> String.valueOf(Math.round(v)));
            put(m, "Intensité", a.path("icu_intensity"), v -> Math.round(v) + " %");
            put(m, "Température", a.path("average_temp"), v -> String.format(Locale.FRANCE, "%.0f °C", v));
            put(m, "RPE (montre)", a.path("icu_rpe"), v -> Math.round(v) + "/10");
            for (Map<String, Object> c : archive.jdbc().queryForList("SELECT metric, value FROM computed_metric WHERE athlete_id = ? AND activity_id = ?",
                    archive.athlete(), r.get("id"))) {
                String key = (String) c.get("metric");
                double v = ((Number) c.get("value")).doubleValue();
                String label = COMPUTED.get(key);
                if (label != null) {
                    m.put(label, key.startsWith("best_time") ? clock((int) Math.round(v)) : key.startsWith("best_power") ? Math.round(v) + " W"
                            : key.equals("cadence_at_easy_pace") ? Math.round(v) + " pas/min" : String.format(Locale.FRANCE, "%.2f", v));
                }
            }
            out.add(m);
        }
        return out;
    }

    private static final Map<String, String> COMPUTED = new java.util.LinkedHashMap<>(Map.of(
            "best_power_5min", "Meilleurs 5 min", "best_power_20min", "Meilleurs 20 min", "best_power_60min", "Meilleure heure",
            "best_time_1km", "Meilleur 1 km", "best_time_5km", "Meilleur 5 km", "stride_at_easy_pace", "Foulée à allure facile (m)",
            "cadence_at_easy_pace", "Cadence à allure facile", "distance_per_stroke", "Distance par mouvement (m)"));
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static void put(Map<String, String> m, String label, tools.jackson.databind.JsonNode n, java.util.function.DoubleFunction<String> f) {
        if (n.isNumber() && n.asDouble() > 0) {
            m.put(label, f.apply(n.asDouble()));
        }
    }

    private static String clock(int seconds) {
        return seconds >= 3600 ? seconds / 3600 + " h " + String.format("%02d", seconds % 3600 / 60)
                : seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private static String pace(double seconds) {
        long s = Math.round(seconds);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public boolean trend(String metricKey, int months) {
        return MetricCatalog.ALL.stream().filter(m -> m.key().equals(metricKey)).findFirst()
                .map(m -> {
                    trends.send(m, months);
                    return true;
                }).orElse(false);
    }

    @Override
    public List<String> metrics() {
        return MetricCatalog.ALL.stream().map(m -> m.key() + ": " + m.label()).toList();
    }

    private static String morning(Map<String, Object> r) {
        List<String> parts = new ArrayList<>();
        if (r.get("hrv") instanceof Number v) {
            parts.add("VFC " + Math.round(v.doubleValue()) + " ms");
        }
        if (r.get("rhr") instanceof Number v) {
            parts.add("FC de repos " + v.intValue());
        }
        if (r.get("sleep") instanceof Number v) {
            int m = v.intValue() / 60;
            parts.add("sommeil " + m / 60 + "h" + String.format("%02d", m % 60));
        }
        if (r.get("score") instanceof Number v) {
            parts.add("score de sommeil " + v.intValue());
        }
        if (r.get("weight") instanceof Number v) {
            parts.add(String.format(Locale.FRANCE, "poids %.1f kg", v.doubleValue()));
        }
        if (r.get("fitness") instanceof Number f && r.get("fatigue") instanceof Number g) {
            parts.add("forme de fond " + Math.round(f.doubleValue()) + ", fatigue " + Math.round(g.doubleValue()));
        }
        return parts.isEmpty() ? "" : r.get("day") + " " + String.join(", ", parts);
    }
}
