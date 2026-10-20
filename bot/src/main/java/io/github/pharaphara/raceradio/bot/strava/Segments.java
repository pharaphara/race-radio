package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.archive.StreamCache;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.charts.Charts;
import io.github.pharaphara.raceradio.charts.Png;
import io.github.pharaphara.raceradio.charts.Theme;
import io.github.pharaphara.raceradio.core.segments.Geo;
import io.github.pharaphara.raceradio.core.segments.SegmentMatcher;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import org.springframework.jdbc.core.JdbcTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /segment}: the athlete's regular segments and their history, Strava's passages and the ones
 * timed locally together, and {@code /segment nouveau} for segments of their own. Local only: no call
 * to Strava, no call to the model.
 */
public final class Segments {

    static final int TOP = 12;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yy", Locale.FRANCE);
    private static final Pattern NEW = Pattern.compile(
            "(?i)^nouveau\\s+(\\d{4}-\\d{2}-\\d{2}|aujourd'hui|hier)\\s+(\\d{1,3})\\s+(\\d{1,3})\\s+(.+)$");
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    record Effort(LocalDate day, int seconds, Double watts, Double hr) {
    }

    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;
    private final StravaHarvest harvest;
    private final TelegramClient telegram;
    private final long chatId;
    private LocalSegments local;
    private SegmentLibrary library;
    private StreamCache streams;
    private Clock clock = Clock.systemDefaultZone();

    public Segments(AthleteDb db, StravaHarvest harvest, TelegramClient telegram, long chatId) {
        this.jdbc = db.jdbc();
        this.athlete = db.id();
        this.harvest = harvest;
        this.telegram = telegram;
        this.chatId = chatId;
    }

    /** Gives the command the locally timed passages and what it needs to create segments. */
    public Segments withLocal(LocalSegments local, SegmentLibrary library, StreamCache streams, Clock clock) {
        this.local = local;
        this.library = library;
        this.streams = streams;
        this.clock = clock;
        return this;
    }

    public void handle(String text) {
        String rest = text.replaceFirst("(?i)^/segments?(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            telegram.send(chatId, overview());
            return;
        }
        if (rest.toLowerCase(Locale.ROOT).startsWith("nouveau")) {
            telegram.send(chatId, create(rest));
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
        StringBuilder b = new StringBuilder();
        // Only the administrator harvests Strava; the others are timed on their GPS from the start.
        if (harvest != null) {
            StravaHarvest.Progress p = harvest.progress();
            if (p.activities() > 0 && !p.done()) {
                b.append("Récupération Strava en cours : ").append(p.detailed()).append(" activités sur ").append(p.activities())
                        .append(", ").append(p.segmentsWithTrace()).append(" segments sur ").append(p.segments()).append(".\n\n");
            }
            harvest.ended().ifPresent(d -> b.append("Strava ne donne plus tes passages depuis le ").append(DAY.format(d))
                    .append(" : je les chronomètre moi-même sur ta trace GPS.\n\n"));
        }
        List<Map<String, Object>> top = jdbc.queryForList("SELECT id, name FROM segment").stream()
                .map(s -> {
                    List<Effort> e = efforts(((Number) s.get("id")).longValue());
                    return Map.<String, Object>of("name", s.get("name"), "n", e.size(),
                            "best", e.stream().mapToInt(Effort::seconds).min().orElse(0));
                })
                .filter(s -> ((Integer) s.get("n")) > 0)
                .sorted(Comparator.comparingInt((Map<String, Object> s) -> (Integer) s.get("n")).reversed())
                .limit(TOP).toList();
        if (top.isEmpty()) {
            return b.append("Aucun passage archivé pour l'instant.").toString();
        }
        b.append("Tes segments les plus fréquentés :");
        for (Map<String, Object> r : top) {
            b.append("\n• ").append(r.get("name")).append(" · ").append(r.get("n")).append(" passages, record ")
                    .append(clock((Integer) r.get("best")));
        }
        return b.append("\n\nDétail : /segment suivi du nom (ou d'un morceau du nom).")
                .append("\nTon propre segment : /segment nouveau 2026-10-16 25 45 Montée du col (de la 25e à la 45e minute).").toString();
    }

    /**
     * {@code nouveau <date> <from> <to> <name>}: a segment from minutes {@code from} to {@code to} of
     * that day's longest activity with a GPS track, then timed on every activity that crosses it.
     */
    String create(String rest) {
        Matcher m = NEW.matcher(rest);
        if (!m.matches() || local == null) {
            return "Format : /segment nouveau AAAA-MM-JJ début fin nom, en minutes depuis le départ.\n"
                    + "Exemple : /segment nouveau 2026-10-16 25 45 Montée du col";
        }
        LocalDate day = switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "aujourd'hui" -> LocalDate.now(clock);
            case "hier" -> LocalDate.now(clock).minusDays(1);
            default -> LocalDate.parse(m.group(1));
        };
        int from = Integer.parseInt(m.group(2)) * 60;
        int to = Integer.parseInt(m.group(3)) * 60;
        String name = m.group(4).strip();
        if (to - from < 60) {
            return "Un segment dure au moins une minute.";
        }
        List<Map<String, Object>> activities = jdbc.queryForList("SELECT id FROM archive_activity WHERE athlete_id = ? AND day = ? "
                + "ORDER BY json_extract(json, '$.moving_time') DESC", athlete, day.toString());
        for (Map<String, Object> a : activities) {
            String id = (String) a.get("id");
            var track = SegmentLibrary.track(streams.get(id));
            if (track.isEmpty()) {
                continue;
            }
            List<double[]> points = new ArrayList<>();
            SegmentMatcher.Track t = track.get();
            for (int i = 0; i < t.length(); i++) {
                if (t.time()[i] >= from && t.time()[i] <= to && !Double.isNaN(t.lat()[i]) && (points.isEmpty() || i % 5 == 0)) {
                    points.add(new double[] {t.lat()[i], t.lng()[i]});
                }
            }
            if (points.size() < 3) {
                return "Pas assez de points GPS entre la " + from / 60 + "e et la " + to / 60 + "e minute ce jour-là.";
            }
            double metres = 0;
            for (int i = 1; i < points.size(); i++) {
                metres += Geo.distance(points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1]);
            }
            Long min = jdbc.queryForObject("SELECT MIN(id) FROM segment", Long.class);
            long newId = Math.min(-1, (min == null ? 0 : min) - 1);
            Map<String, Object> detail = Map.of("name", name, "distance", Math.round(metres), "points", points, "custom", true);
            jdbc.update("INSERT INTO segment (id, origin, name, detail, fetched_at, added_by) VALUES (?, 'custom', ?, ?, ?, ?)",
                    newId, name, JSON.writeValueAsString(detail), clock.instant().toString(), athlete);
            library.reload();
            int scanned = local.scanPending(Integer.MAX_VALUE);
            List<Effort> efforts = efforts(newId);
            return String.format(Locale.FRANCE, "Segment « %s » créé : %.1f km. Je l'ai cherché dans tes %d activités archivées : %d passage%s.",
                    name, metres / 1000, scanned, efforts.size(), efforts.size() > 1 ? "s" : "")
                    + (efforts.isEmpty() ? "" : "\n\n" + history(name, efforts));
        }
        return "Aucune activité avec trace GPS le " + day + ".";
    }

    private List<Map<String, Object>> find(String words) {
        String q = plain(words);
        return jdbc.queryForList("SELECT id, name FROM segment").stream()
                .map(r -> Map.<String, Object>of("id", r.get("id"), "name", r.get("name"), "n", efforts(((Number) r.get("id")).longValue()).size()))
                .filter(r -> (Integer) r.get("n") > 0 && plain((String) r.get("name")).contains(q))
                .sorted(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("n")).reversed())
                .toList();
    }

    List<Effort> efforts(long segmentId) {
        if (local != null) {
            return local.history(segmentId).stream()
                    .map(e -> new Effort(e.day(), (int) Math.round(e.seconds()), e.watts(), e.heartRate())).toList();
        }
        return jdbc.queryForList("SELECT day, elapsed_seconds, average_watts, average_hr FROM strava_effort "
                + "WHERE athlete_id = ? AND segment_id = ? ORDER BY day", athlete, segmentId).stream().map(r -> new Effort(LocalDate.parse((String) r.get("day")), ((Number) r.get("elapsed_seconds")).intValue(),
                r.get("average_watts") instanceof Number w ? w.doubleValue() : null,
                r.get("average_hr") instanceof Number h ? h.doubleValue() : null)).toList();
    }

    static String history(String name, List<Effort> efforts) {
        Effort best = efforts.stream().min(Comparator.comparingInt(Effort::seconds)).orElseThrow();
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
