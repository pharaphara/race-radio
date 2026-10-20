package io.github.pharaphara.raceradio.bot.archive;

import io.github.pharaphara.raceradio.bot.archive.MetricCatalog.Group;
import io.github.pharaphara.raceradio.bot.archive.MetricCatalog.Metric;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.charts.Charts;
import io.github.pharaphara.raceradio.charts.Png;
import io.github.pharaphara.raceradio.charts.Theme;
import io.github.pharaphara.raceradio.core.metrics.Trend;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code /tendance}: one figure over months, from the local archive only. No call to Intervals.icu,
 * no call to the model: the numbers and the sentence around them are the program's.
 */
public final class Trends {

    static final int DEFAULT_MONTHS = 12;
    static final int MIN_SESSIONS = 4;
    private static final Pattern PERIOD = Pattern.compile("(?i)(\\d{1,2})\\s*(mois|m\\b)|(\\d)?\\s*\\ban(s|nee|née)?\\b");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.FRANCE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE);

    private final Archive archive;
    private final TelegramClient telegram;
    private final long chatId;
    private final Clock clock;

    public Trends(Archive archive, TelegramClient telegram, long chatId, Clock clock) {
        this.archive = archive;
        this.telegram = telegram;
        this.chatId = chatId;
        this.clock = clock;
    }

    /** Metrics read from the heart-rate strap, which a change of strap makes less comparable. */
    static final java.util.Set<String> HEART_RATE = java.util.Set.of("velo_efficacite", "velo_derive", "velo_fc_endurance",
            "course_efficacite", "course_derive", "fc_max_tenue", "fc_repos");
    private LocalDate strapSince;

    /** When the current heart-rate strap came into use; months before it are flagged. Null when unknown. */
    public Trends withStrapSince(LocalDate since) {
        this.strapSince = since;
        return this;
    }

    public void handle(String text) {
        String rest = text.replaceFirst("(?i)^/tendances?(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            telegram.send(chatId, catalogue());
            return;
        }
        int months = DEFAULT_MONTHS;
        Matcher p = PERIOD.matcher(rest);
        if (p.find()) {
            months = p.group(1) != null ? Integer.parseInt(p.group(1)) : 12 * (p.group(3) == null ? 1 : Integer.parseInt(p.group(3)));
            rest = (rest.substring(0, p.start()) + " " + rest.substring(p.end())).strip();
        }
        months = Math.clamp(months, 2, 36);
        if (MetricCatalog.plain(rest).matches(".*\\b(km/h|w/kmh|watts?/km)\\b.*")) {
            telegram.send(chatId, "Les watts par km/h ne mesurent pas la forme sur la route : le vent, la pente et la position "
                    + "changent la vitesse bien plus que toi. Suis plutôt l'efficacité aérobie (/tendance vélo efficacité) "
                    + "ou tes records de puissance (/tendance vélo 20min).");
            return;
        }
        Optional<Metric> m = MetricCatalog.find(rest);
        if (m.isEmpty()) {
            telegram.send(chatId, "Je ne connais pas cette mesure.\n\n" + catalogue());
            return;
        }
        send(m.get(), months);
    }

    /** The chart, then the figures, to the athlete. */
    public void send(Metric m, int months) {
        Answer a = answer(m, months);
        if (a.chart() != null) {
            telegram.sendPhoto(chatId, a.chart(), null);
        }
        telegram.send(chatId, a.text());
    }

    public record Answer(String text, byte[] chart) {
    }

    public Answer answer(Metric m, int months) {
        LocalDate today = LocalDate.now(clock);
        YearMonth to = YearMonth.from(today);
        YearMonth from = to.minusMonths(months - 1L);
        List<Trend.Point> points = points(m, from.atDay(1), today);
        Trend.Result r = Trend.of(points, m.aggregate(), from, to);
        String head = capital(m.label()) + (m.group() == Group.DAILY ? "" : " (" + m.group().label() + ")")
                + ", " + months + " derniers mois";
        String count = m.group() == Group.DAILY ? r.sessions() + " jours mesurés" : r.sessions() + " séances comparables : " + m.comparable();
        StringBuilder b = new StringBuilder(head).append("\n").append(count).append(".");
        if (r.sessions() < MIN_SESSIONS) {
            b.append("\nTrop peu de points pour parler de tendance.");
        } else if (!Double.isNaN(r.change())) {
            String how = m.aggregate() == Trend.Aggregate.MEDIAN ? "valeur typique" : "meilleure valeur";
            b.append("\nDébut de période (").append(how).append(") ").append(m.format().apply(r.first()))
                    .append(", fin ").append(m.format().apply(r.last()))
                    .append(String.format(Locale.FRANCE, " (%+.0f %%)", 100 * r.change())).append(verdict(m, r.change())).append(".");
        }
        if (r.best() != null && m.aggregate() != Trend.Aggregate.MEDIAN) {
            b.append("\nRecord de la période : ").append(m.format().apply(r.best().value())).append(" le ").append(DAY.format(r.best().day())).append(".");
        }
        if (strapSince != null && HEART_RATE.contains(m.key()) && from.atDay(1).isBefore(strapSince)) {
            b.append("\nAvant le ").append(DAY.format(strapSince)).append(", la FC venait d'une autre ceinture : ces mois-là se comparent moins bien.");
        }
        b.append("\nArchive locale : ").append(archive.activities()).append(" activités")
                .append(archive.lastSync().map(d -> ", à jour au " + DAY.format(d)).orElse("")).append(".");
        return new Answer(b.toString(), r.sessions() == 0 ? null : chart(m, r, head));
    }

    List<Trend.Point> points(Metric m, LocalDate from, LocalDate to) {
        String sql = m.group() == Group.DAILY
                ? "SELECT w.day AS day, " + m.sql() + " AS v FROM archive_wellness w WHERE w.athlete_id = ? AND w.day BETWEEN ? AND ?"
                : "SELECT a.day AS day, " + m.sql() + " AS v FROM archive_activity a WHERE a.athlete_id = ? AND a.type IN ("
                + m.group().types + ") AND a.day BETWEEN ? AND ? AND (" + m.filter() + ")";
        return archive.jdbc().queryForList(sql, archive.athlete(), from.toString(), to.toString()).stream()
                .filter(row -> row.get("v") instanceof Number)
                .map(row -> new Trend.Point(LocalDate.parse((String) row.get("day")), ((Number) row.get("v")).doubleValue()))
                .filter(pt -> pt.value() > 0 || m.key().endsWith("derive"))
                .toList();
    }

    private static String verdict(Metric m, double change) {
        if (Math.abs(change) < 0.01) {
            return ", stable";
        }
        if (m.higherBetter() == null) {
            return change > 0 ? ", en hausse" : ", en baisse";
        }
        return (change > 0) == m.higherBetter() ? ", en progrès" : ", en recul";
    }

    private static byte[] chart(Metric m, Trend.Result r, String title) {
        int n = r.months().size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
            y[i] = r.months().get(i).value();
        }
        String sub = m.aggregate() == Trend.Aggregate.MEDIAN ? "valeur typique du mois (médiane)" : "meilleure valeur du mois";
        var panel = Charts.Panel.of("", List.of(new Charts.Series(m.label(), y, 0)), m.format()::apply).forSession().fullRange();
        String svg = Charts.panels(title, sub, x, i -> r.months().get((int) Math.round(i)).month().format(MONTH),
                n <= 8 ? 1 : n <= 16 ? 2 : 3, List.of(panel), List.of(), Theme.DARK);
        return Png.of(svg, Charts.WIDTH, 2);
    }

    static String catalogue() {
        Map<Group, List<Metric>> byGroup = MetricCatalog.ALL.stream().collect(Collectors.groupingBy(Metric::group,
                () -> new java.util.EnumMap<>(Group.class), Collectors.toList()));
        StringBuilder b = new StringBuilder("Ce que je peux suivre, depuis l'archive locale :");
        byGroup.forEach((g, list) -> {
            b.append("\n\n").append(capital(g.label())).append(" :");
            list.forEach(m -> b.append("\n• ").append(m.label()).append(" · ").append(m.words().getFirst()));
        });
        return b.append("\n\nExemples : /tendance vélo efficacité 12 mois · /tendance course 5km 1 an · /tendance vfc 6 mois").toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
