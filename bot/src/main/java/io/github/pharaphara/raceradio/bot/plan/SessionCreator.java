package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.PromptFence;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Workout;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.icu.workout.WorkoutText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One workout on demand, outside any programme: {@code /seance demain 3x10min à 280W}.
 *
 * <p>The model only translates the request into a {@link Draft}, with intensities as a share of
 * threshold power or an offset from threshold pace, as the athlete said them. The arithmetic and the
 * bounds are {@link Draft#build}'s, the syntax is {@link WorkoutText}'s, and nothing is published
 * before the athlete has seen the workout and pressed Publier. Once published, it can be taken
 * back with one button, like anything else the coach writes.
 */
public final class SessionCreator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionCreator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUESTION = "QUESTION:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE);
    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("lun", DayOfWeek.MONDAY), Map.entry("mar", DayOfWeek.TUESDAY), Map.entry("mer", DayOfWeek.WEDNESDAY),
            Map.entry("jeu", DayOfWeek.THURSDAY), Map.entry("ven", DayOfWeek.FRIDAY), Map.entry("sam", DayOfWeek.SATURDAY),
            Map.entry("dim", DayOfWeek.SUNDAY));
    private static final Pattern DAY_WORD = Pattern.compile(
            "(?i)^(aujourd'hui|aujourdhui|auj|demain|après-demain|apres-demain|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|lun|mar|mer|jeu|ven|sam|dim|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2})\\b\\s*");

    /** A draft waiting for the athlete's decision. Lost on restart, which only means asking again. */
    public record Pending(LocalDate day, Workout workout) {
    }

    private final CoachModel model;
    private final Planner planner;
    private final IntervalsClient intervals;
    private final IntervalsWriter writer;
    private final TelegramClient telegram;
    private final long chatId;
    private final Clock clock;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public SessionCreator(CoachModel model, Planner planner, IntervalsClient intervals, IntervalsWriter writer,
                          TelegramClient telegram, long chatId, Clock clock) {
        this.model = model;
        this.planner = planner;
        this.intervals = intervals;
        this.writer = writer;
        this.telegram = telegram;
        this.chatId = chatId;
        this.clock = clock;
    }

    /** What a request gave: a workout to show, or only a message (help, question, refusal). */
    public record Prepared(String message, Pending pending) {
    }

    /** Handles {@code /seance ...}: drafts, then shows the workout with Publier and Annuler. */
    public void draft(String text) {
        Prepared p = prepare(text);
        if (p.pending() == null) {
            telegram.send(chatId, p.message());
            return;
        }
        long id = ids.incrementAndGet();
        pending.put(id, p.pending());
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("Publier", "pub:" + id);
        buttons.put("Annuler", "drop:" + id);
        telegram.sendWithButtons(chatId, p.message(), buttons);
    }

    /** Reads the request and drafts the workout, without sending or publishing anything. */
    public Prepared prepare(String text) {
        String rest = text.replaceFirst("(?i)^/s[eé]ance(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            return new Prepared("""
                    Décris la séance, avec le jour si ce n'est pas aujourd'hui :
                    /seance demain vélo 3x10min à 280W, récup 5 min
                    /seance samedi course 1h15 facile avec 6 lignes droites
                    /seance mer natation technique 45 min
                    Je te montre la séance avant de la publier.""", null);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate day;
        try {
            Matcher m = DAY_WORD.matcher(rest);
            if (m.find()) {
                day = day(m.group(1), today);
                rest = rest.substring(m.end()).strip();
            } else {
                day = today;
            }
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return new Prepared("Je n'ai pas compris le jour. Écris aujourd'hui, demain, un jour de la semaine ou une date (2026-11-03).", null);
        }
        if (day.isBefore(today)) {
            return new Prepared("Ce jour est passé.", null);
        }
        if (rest.isEmpty()) {
            return new Prepared("Et quelle séance ? Par exemple : /seance demain vélo 1h endurance", null);
        }
        Thresholds t = planner.thresholds();
        Workout w;
        try {
            ModelReply r = model.ask(Purpose.SESSION, "low", 2_000, prompt(rest, t));
            String answer = r.text().strip();
            if (r.refused() || answer.isEmpty()) {
                return new Prepared("Je n'ai pas pu préparer cette séance.", null);
            }
            if (answer.startsWith(QUESTION)) {
                return new Prepared(answer.substring(QUESTION.length()).strip(), null);
            }
            w = buildable(JSON.readValue(json(answer), Draft.class).build(t));
        } catch (CoachModel.BudgetExhausted e) {
            return new Prepared("Le budget du modèle est atteint ce mois-ci : je ne peux pas préparer de séance.", null);
        } catch (IllegalArgumentException e) {
            return new Prepared("Je ne peux pas construire cette séance : " + e.getMessage()
                    + "\nReformule, ou précise les intensités.", null);
        } catch (RuntimeException e) {
            LOG.warn("drafting a workout failed: {}", e.toString());
            return new Prepared("Je n'ai pas réussi à préparer cette séance. Reformule-la plus simplement.", null);
        }
        return new Prepared(preview(day, w, t, alreadyPlanned(day)), new Pending(day, w));
    }

    /** True for the buttons this class put under its messages. */
    public boolean handles(String data) {
        return data.startsWith("pub:") || data.startsWith("drop:") || data.startsWith("del:");
    }

    public void callback(String data) {
        String[] parts = data.split(":", 2);
        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        switch (parts[0]) {
            case "drop" -> telegram.send(chatId, pending.remove(id) == null ? "Déjà traité." : "Séance abandonnée, rien n'a été publié.");
            case "pub" -> publish(id);
            case "del" -> {
                try {
                    writer.deleteOwnEvent(id);
                    telegram.send(chatId, "Séance retirée du calendrier.");
                } catch (RuntimeException e) {
                    telegram.send(chatId, "Je n'ai pas pu la retirer : " + e.getMessage());
                }
            }
            default -> { }
        }
    }

    private void publish(long id) {
        Pending p = pending.remove(id);
        if (p == null) {
            telegram.send(chatId, "Ce brouillon n'existe plus (déjà publié, abandonné, ou le service a redémarré). Redemande la séance.");
            return;
        }
        Optional<CalendarEvent> created;
        try {
            created = writer.publishWorkout(p.day(), p.workout());
        } catch (IntervalsWriter.WorkoutNotUnderstood e) {
            telegram.send(chatId, "Intervals.icu n'a pas compris la séance comme prévu, je l'ai retirée : " + String.join(", ", e.disagreements()));
            return;
        } catch (RuntimeException e) {
            telegram.send(chatId, "La publication a échoué : " + e.getMessage());
            return;
        }
        if (created.isEmpty()) {
            telegram.send(chatId, "Mode sec : la séance n'a pas été publiée dans Intervals.icu.");
            return;
        }
        telegram.sendWithButtons(chatId, "« " + p.workout().name() + " » publiée pour " + DAY.format(p.day())
                        + ". Elle partira vers ta montre et MyWhoosh à la prochaine synchronisation.",
                Map.of("Retirer du calendrier", "del:" + created.get().id()));
    }

    /** Cues are the only free text the calendar might misread; drop them rather than lose the workout. */
    static Workout buildable(Workout w) {
        if (WorkoutText.problems(w).isEmpty()) {
            return w;
        }
        Workout bare = new Workout(w.sport(), w.name(), w.sections().stream()
                .map(s -> new io.github.pharaphara.raceradio.core.workout.Section(s.title(), s.repeats(),
                        s.steps().stream().map(st -> new io.github.pharaphara.raceradio.core.workout.Step(st.length(), st.target(), st.cadence(), "")).toList()))
                .toList());
        List<String> problems = WorkoutText.problems(bare);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return bare;
    }

    private String alreadyPlanned(LocalDate day) {
        try {
            List<String> names = intervals.events(day, day).stream()
                    .filter(e -> "WORKOUT".equals(e.category())).map(CalendarEvent::name).toList();
            return names.isEmpty() ? "" : "\nDéjà prévu ce jour-là : " + String.join(", ", names) + ". Je l'ajoute à côté, sans rien remplacer.";
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String preview(LocalDate day, Workout w, Thresholds t, String alreadyPlanned) {
        int seconds = Draft.seconds(w, t);
        String length = seconds >= 3600 ? seconds / 3600 + "h" + String.format("%02d", seconds % 3600 / 60) : Math.round(seconds / 60.0) + " min";
        return "« " + w.name() + " » · " + sportName(w.sport()) + " · " + length + " · " + DAY.format(day) + "\n\n"
                + WorkoutText.render(w) + "\n" + alreadyPlanned + "\n\nJe la publie ?";
    }

    private static String sportName(String sport) {
        return switch (sport) {
            case "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> "vélo";
            case "Swim", "OpenWaterSwim" -> "natation";
            default -> "course";
        };
    }

    static LocalDate day(String word, LocalDate today) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.startsWith("auj")) {
            return today;
        }
        if (w.equals("demain")) {
            return today.plusDays(1);
        }
        if (w.startsWith("apr")) {
            return today.plusDays(2);
        }
        if (w.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(w);
        }
        if (w.matches("\\d{1,2}/\\d{1,2}")) {
            String[] dm = w.split("/");
            LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(dm[1]), Integer.parseInt(dm[0]));
            return d.isBefore(today) ? d.plusYears(1) : d;
        }
        DayOfWeek dow = WEEKDAYS.get(w.substring(0, 3));
        if (dow == null) {
            throw new IllegalArgumentException(word);
        }
        return today.with(TemporalAdjusters.nextOrSame(dow));
    }

    /** The model's answer may come wrapped in a code fence. */
    static String json(String answer) {
        int from = answer.indexOf('{');
        int to = answer.lastIndexOf('}');
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("the answer holds no workout");
        }
        return answer.substring(from, to + 1);
    }

    static String prompt(String request, Thresholds t) {
        return """
                Task: turn the athlete's request into one workout, as JSON and nothing else. Do not compute
                watts or paces: express intensities the way below, the service does the arithmetic.

                Athlete's thresholds (for your judgement only): threshold power %s, threshold pace %s,
                critical swim speed %s.

                JSON shape:
                {"sport": "Ride" | "VirtualRide" | "Run" | "TrailRun" | "Swim",
                 "name": "short French title, no digits",
                 "sections": [{"title": "Warmup" | "Main Set" | "Cooldown" | "Drills",
                               "repeats": 1,
                               "steps": [{"seconds": 600, "metres": null, "kind": "...", "low": 0, "high": null,
                                          "cadence": null, "cue": ""}]}]}

                kind, with low and high:
                - THRESHOLD_POWER_PERCENT: percent of threshold power (bike), e.g. 88 and 94
                - WATTS: absolute watts, only when the athlete gave watts
                - POWER_ZONE: zone 1 to 7 in low (bike)
                - HEART_RATE_ZONE: zone 1 to 7 in low (any sport)
                - THRESHOLD_PACE_OFFSET: seconds slower than threshold pace per km on foot, or than critical
                  swim speed per 100 m in the water; negative is faster. For a range, low is the faster end.
                - PACE: absolute seconds per km (run) or per 100 m (swim), only when the athlete gave a pace

                Rules:
                - Follow the request exactly: its sport, durations, repetitions and intensities. When the
                  athlete gives numbers, use them as given (WATTS, PACE).
                - When the athlete names an intensity without a number, bike in percent of threshold power:
                  recovery 45-55, endurance 56-75, tempo 76-87, sweet spot 88-94, threshold 95-105,
                  VO2max 106-120, anaerobic 121-150. Run as offsets from threshold pace: easy +50 to +70,
                  steady +25 to +40, tempo +10 to +20, threshold -5 to +5, VO2max -15 to -25.
                  Swim as offsets from critical swim speed: recovery +15 to +20, aerobic +8 to +12,
                  tempo +3 to +6, threshold 0, VO2max -3 to -5.
                - Unless the request says otherwise, add a warm-up (10-15 min) and a cool-down (5-10 min)
                  around any hard work, and keep the total to the duration asked.
                - A repeated block is one section with "repeats" greater than 1, holding the work step and
                  its recovery.
                - seconds or metres, never both. Swim sets in metres. cadence in rpm, bike only, or null.
                - cue: at most a few French words, no digits, no units, no symbols; empty is fine.
                - If the request is not a workout, or the sport cannot be guessed, answer instead one line
                  starting with QUESTION: followed by a short question in French.

                """.formatted(t.thresholdPower() == null ? "unknown" : t.thresholdPower() + " W",
                t.thresholdPace() == null ? "unknown" : t.thresholdPace() / 60 + ":" + String.format("%02d", t.thresholdPace() % 60) + "/km",
                t.criticalSwimPace() == null ? "unknown" : t.criticalSwimPace() / 60 + ":" + String.format("%02d", t.criticalSwimPace() % 60) + "/100m")
                + PromptFence.data("request", request);
    }
}
