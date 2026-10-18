package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.charts.Charts;
import io.github.pharaphara.raceradio.charts.Png;
import io.github.pharaphara.raceradio.charts.Theme;
import org.springframework.jdbc.core.JdbcTemplate;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /segment}: the athlete's regular segments and their history, from the local archive only.
 * No call to Strava, no call to the model.
 */
public final class Segments {

    static final int TOP = 12;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yy", Locale.FRANCE);

    record Effort(LocalDate day, int seconds, Double watts, Double hr) {
    }

    private final JdbcTemplate jdbc;
    private final StravaHarvest harvest;
    private final TelegramClient telegram;
    private final long chatId;

    public Segments(JdbcTemplate jdbc, StravaHarvest harvest, TelegramClient telegram, long chatId) {
        this.jdbc = jdbc;
        this.harvest = harvest;
        this.telegram = telegram;
        this.chatId = chatId;
    }

    public void handle(String text) {
        String rest = text.replaceFirst("(?i)^/segments?(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            telegram.send(chatId, overview());
            return;
        }
        List<Map<String, Object>> found = find(rest);
        if (found.isEmpty()) {
            telegram.send(chatId, "Aucun de tes segments ne s'appelle comme ça.\n\n" + overview());
            return;
        }
        if (found.size() > 1 && !plain((String) found.getFirst().get("name")).equals(plain(rest))) {
            StringBuilder b = new StringBuilder("Plusieurs segments correspondent, précise :");
            found.stream().limit(8).forEach(s -> b.append("\n• ").append(s.get("name")).append(" (").append(s.get("n")).append(" passages)"));
            telegram.send(chatId, b.toString());
            return;
        }
        long id = ((Number) found.getFirst().get("id")).longValue();
        String name = (String) found.getFirst().get("name");
        List<Effort> efforts = efforts(id);
        byte[] chart = chart(name, efforts);
        if (chart != null) {
            telegram.sendPhoto(chatId, chart, null);
        }
        telegram.send(chatId, history(name, efforts));
    }

    String overview() {
        StravaHarvest.Progress p = harvest.progress();
        StringBuilder b = new StringBuilder();
        if (!p.done()) {
            b.append("Récupération Strava en cours : ").append(p.detailed()).append(" activités sur ").append(p.activities())
                    .append(", ").append(p.segmentsWithTrace()).append(" segments sur ").append(p.segments()).append(".\n\n");
        }
        List<Map<String, Object>> top = jdbc.queryForList("SELECT s.name, COUNT(*) AS n, MIN(e.elapsed_seconds) AS best, MAX(e.day) AS last "
                + "FROM strava_effort e JOIN strava_segment s ON s.id = e.segment_id GROUP BY s.id ORDER BY n DESC, last DESC LIMIT ?", TOP);
        if (top.isEmpty()) {
            return b.append("Aucun passage archivé pour l'instant.").toString();
        }
        b.append("Tes segments les plus fréquentés :");
        for (Map<String, Object> r : top) {
            b.append("\n• ").append(r.get("name")).append(" · ").append(r.get("n")).append(" passages, record ")
                    .append(clock(((Number) r.get("best")).intValue()));
        }
        return b.append("\n\nDétail : /segment suivi du nom (ou d'un morceau du nom).").toString();
    }

    private List<Map<String, Object>> find(String words) {
        String q = plain(words);
        return jdbc.queryForList("SELECT s.id, s.name, COUNT(*) AS n FROM strava_segment s JOIN strava_effort e ON e.segment_id = s.id "
                        + "GROUP BY s.id ORDER BY n DESC").stream()
                .filter(r -> plain((String) r.get("name")).contains(q))
                .toList();
    }

    List<Effort> efforts(long segmentId) {
        return jdbc.queryForList("SELECT day, elapsed_seconds, average_watts, average_hr FROM strava_effort WHERE segment_id = ? ORDER BY day",
                segmentId).stream().map(r -> new Effort(LocalDate.parse((String) r.get("day")), ((Number) r.get("elapsed_seconds")).intValue(),
                r.get("average_watts") instanceof Number w ? w.doubleValue() : null,
                r.get("average_hr") instanceof Number h ? h.doubleValue() : null)).toList();
    }

    static String history(String name, List<Effort> efforts) {
        Effort best = efforts.stream().min(java.util.Comparator.comparingInt(Effort::seconds)).orElseThrow();
        Effort last = efforts.getLast();
        StringBuilder b = new StringBuilder(name).append("\n").append(efforts.size()).append(" passages archivés, record ")
                .append(clock(best.seconds())).append(" le ").append(DAY.format(best.day())).append(describe(best)).append(".");
        if (last != best) {
            b.append("\nDernier passage le ").append(DAY.format(last.day())).append(" : ").append(clock(last.seconds()))
                    .append(String.format(Locale.FRANCE, " (%+d s sur le record)", last.seconds() - best.seconds())).append(describe(last)).append(".");
        }
        b.append("\n\nDerniers passages :");
        efforts.reversed().stream().limit(8).forEach(e -> b.append("\n").append(DAY.format(e.day())).append(" · ")
                .append(clock(e.seconds())).append(describe(e)));
        return b.toString();
    }

    private static String describe(Effort e) {
        return (e.watts() == null ? "" : ", " + Math.round(e.watts()) + " W") + (e.hr() == null ? "" : ", FC " + Math.round(e.hr()));
    }

    private static byte[] chart(String name, List<Effort> efforts) {
        if (efforts.size() < 2) {
            return null;
        }
        LocalDate first = efforts.getFirst().day();
        double[] x = efforts.stream().mapToDouble(e -> ChronoUnit.DAYS.between(first, e.day())).toArray();
        double[] y = efforts.stream().mapToDouble(Effort::seconds).toArray();
        var panel = Charts.Panel.of("", List.of(new Charts.Series("temps", y, 0)), s -> clock((int) Math.round(s))).forSession().fullRange();
        String svg = Charts.panels(name, "temps de chaque passage (plus bas, plus rapide)", x,
                d -> DAY.format(first.plusDays(Math.round(d))), Double.NaN, List.of(panel), List.of(), Theme.DARK);
        return Png.of(svg, Charts.WIDTH, 2);
    }

    static String clock(int seconds) {
        return seconds >= 3600 ? seconds / 3600 + "h" + String.format("%02d:%02d", seconds % 3600 / 60, seconds % 60)
                : seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
