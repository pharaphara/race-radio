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
            + "FROM archive_wellness ";

    private final Archive archive;
    private final Trends trends;

    public ArchiveHistory(Archive archive, Trends trends) {
        this.archive = archive;
        this.trends = trends;
    }

    @Override
    public List<String> recentDays(int days) {
        String from = archive.today().minusDays(days).toString();
        return archive.jdbc().queryForList(WELLNESS + "WHERE day >= ? ORDER BY day", from).stream()
                .map(ArchiveHistory::morning).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public List<String> recentSessions(int days) {
        return archive.recent(days);
    }

    @Override
    public Optional<String> day(LocalDate day) {
        List<String> lines = new ArrayList<>();
        archive.jdbc().queryForList(WELLNESS + "WHERE day = ?", day.toString()).stream()
                .map(ArchiveHistory::morning).filter(s -> !s.isEmpty())
                .forEach(s -> lines.add("Matin : " + s.substring(11)));
        archive.jdbc().queryForList("SELECT json_extract(json, '$.name') AS name, type, json_extract(json, '$.moving_time') AS t, "
                        + "json_extract(json, '$.icu_training_load') AS load, json_extract(json, '$.average_heartrate') AS hr, "
                        + "json_extract(json, '$.icu_weighted_avg_watts') AS wap, json_extract(json, '$.distance') AS dist "
                        + "FROM archive_activity WHERE day = ? ORDER BY json_extract(json, '$.start_date_local')", day.toString())
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
