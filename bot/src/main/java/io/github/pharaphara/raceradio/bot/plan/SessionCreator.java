package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.archive.Archive;
import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.PromptFence;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.Plans;
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
import tools.jackson.databind.JsonNode;
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
 * One workout on demand, outside any programme: {@code /seance demain cap facile}, or as precise as
 * {@code /seance demain 3x10min à 280W}.
 *
 * <p>The coach takes the initiative: a vague request gets a complete workout, calibrated on what is
 * known locally (the morning verdict, the day's availability, the last two weeks in the archive),
 * with one sentence saying why. The athlete then publishes it, asks for a change in plain words, or
 * drops it. The model only translates into a {@link Draft}; the arithmetic and the bounds are
 * {@link Draft#build}'s, the syntax is {@link WorkoutText}'s, and nothing is published before the
 * athlete has seen the workout and pressed Publier.
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
    private static final Map<String, String> VERDICTS = Map.of("GREEN", "vert, entraînement prévu", "YELLOW", "orange, version allégée",
            "RED", "rouge, repos ou récupération active");
    /** Days of history given to the model to calibrate a workout. */
    static final int RECENT_DAYS = 14;

    /**
     * A draft waiting for the athlete's decision. Lost on restart, which only means asking again.
     *
     * @param draftJson  the draft as the model wrote it, to amend it without starting over
     */
    public record Pending(LocalDate day, Workout workout, String request, String draftJson) {
    }

    /** What a request gave: a workout to show, or only a message (help, question, refusal). */
    public record Prepared(String message, Pending pending) {
    }

    private final CoachModel model;
    private final Planner planner;
    private final IntervalsClient intervals;
    private final IntervalsWriter writer;
    private final Plans plans;
    private final DecisionLog decisions;
    private final Archive archive;
    private final TelegramClient telegram;
    private final long chatId;
    private final Clock clock;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    /** The draft the athlete pressed Modifier on: their next plain message is the change. */
    private volatile Long amending;

    public SessionCreator(CoachModel model, Planner planner, IntervalsClient intervals, IntervalsWriter writer,
                          Plans plans, DecisionLog decisions, Archive archive,
                          TelegramClient telegram, long chatId, Clock clock) {
        this.model = model;
        this.planner = planner;
        this.intervals = intervals;
        this.writer = writer;
        this.plans = plans;
        this.decisions = decisions;
        this.archive = archive;
        this.telegram = telegram;
        this.chatId = chatId;
        this.clock = clock;
    }

    /** Handles {@code /seance ...}: drafts, then shows the workout with its buttons. */
    public void draft(String text) {
        amending = null;
        show(prepare(text));
    }

    /** True while the athlete is expected to say what to change in a draft. */
    public boolean amending() {
        return amending != null;
    }

    /** Any command ends the wait for a change. */
    public void stopAmending() {
        amending = null;
    }

    /** The athlete's change to the draft they pressed Modifier on. */
    public void amend(String change) {
        Long id = amending;
        amending = null;
        Pending p = id == null ? null : pending.get(id);
        if (p == null) {
            telegram.send(chatId, "Ce brouillon n'existe plus. Redemande la séance avec /seance.");
            return;
        }
        Prepared next = draftFor(p.day(), p.request(), p.draftJson(), change);
        if (next.pending() != null) {
            pending.remove(id);
        }
        show(next);
    }

    private void show(Prepared p) {
        if (p.pending() == null) {
            telegram.send(chatId, p.message());
            return;
        }
        long id = ids.incrementAndGet();
        pending.put(id, p.pending());
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("Publier", "pub:" + id);
        buttons.put("Modifier", "edit:" + id);
        buttons.put("Annuler", "drop:" + id);
        telegram.sendWithButtons(chatId, p.message(), buttons);
    }

    /** Reads the request and drafts the workout, without sending or publishing anything. */
    public Prepared prepare(String text) {
        String rest = text.replaceFirst("(?i)^/s[eé]ance(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            return new Prepared("""
                    Dis-moi quoi et quand, je m'occupe du reste :
                    /seance demain cap facile
                    /seance jeudi une VO2 à vélo
                    /seance samedi sortie longue 2h30
                    /seance demain vélo 3x10min à 280W
                    Je te propose une séance calibrée, tu la publies, la modifies ou l'annules.""", null);
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
            return new Prepared("Et quelle séance ? Par exemple : /seance demain cap facile", null);
        }
        return draftFor(day, rest, null, null);
    }

    private Prepared draftFor(LocalDate day, String request, String previousDraft, String change) {
        Thresholds t = planner.thresholds();
        Workout w;
        String why;
        String draftJson;
        try {
            ModelReply r = model.ask(Purpose.SESSION, "low", 2_500, prompt(day, request, t, context(day), previousDraft, change));
            String answer = r.text().strip();
            if (r.refused() || answer.isEmpty()) {
                return new Prepared("Je n'ai pas pu préparer cette séance.", null);
            }
            if (answer.startsWith(QUESTION)) {
                return new Prepared(answer.substring(QUESTION.length()).strip(), null);
            }
            JsonNode root = JSON.readTree(json(answer));
            JsonNode workout = root.has("workout") ? root.path("workout") : root;
            why = root.path("why").isString() ? root.path("why").asString().strip() : "";
            draftJson = workout.toString();
            w = buildable(JSON.treeToValue(workout, Draft.class).build(t));
        } catch (CoachModel.BudgetExhausted e) {
            return new Prepared("Le budget du modèle est atteint ce mois-ci : je ne peux pas préparer de séance.", null);
        } catch (IllegalArgumentException e) {
            return new Prepared("Je ne peux pas construire cette séance : " + e.getMessage()
                    + "\nReformule, ou précise les intensités.", null);
        } catch (RuntimeException e) {
            LOG.warn("drafting a workout failed: {}", e.toString());
            return new Prepared("Je n'ai pas réussi à préparer cette séance. Reformule-la plus simplement.", null);
        }
        return new Prepared(preview(day, w, t, why, alreadyPlanned(day)), new Pending(day, w, request, draftJson));
    }

    /** What a coach would look at before prescribing, all of it local. */
    String context(LocalDate day) {
        StringBuilder b = new StringBuilder();
        LocalDate today = LocalDate.now(clock);
        decisions.latestOf("MORNING").filter(d -> d.day().equals(today.toString())).ifPresent(d ->
                b.append("Morning verdict today (").append(today).append("): ").append(VERDICTS.getOrDefault(d.verdict(), d.verdict())).append('\n'));
        Integer minutes = plans.availability().get(day.getDayOfWeek());
        if (minutes != null) {
            b.append("Time available that day: ").append(minutes).append(" min\n");
        }
        try {
            List<String> recent = archive.recent(RECENT_DAYS);
            b.append("Sessions of the last ").append(RECENT_DAYS).append(" days:\n")
                    .append(recent.isEmpty() ? "none recorded\n" : String.join("\n", recent) + "\n");
        } catch (RuntimeException e) {
            b.append("Recent sessions: unavailable\n");
        }
        return b.toString();
    }

    /** True for the buttons this class put under its messages. */
    public boolean handles(String data) {
        return data.startsWith("pub:") || data.startsWith("drop:") || data.startsWith("del:") || data.startsWith("edit:");
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
            case "drop" -> {
                amending = null;
                telegram.send(chatId, pending.remove(id) == null ? "Déjà traité." : "Séance abandonnée, rien n'a été publié.");
            }
            case "pub" -> {
                amending = null;
                publish(id);
            }
            case "edit" -> {
                if (!pending.containsKey(id)) {
                    telegram.send(chatId, "Ce brouillon n'existe plus. Redemande la séance avec /seance.");
                    return;
                }
                amending = id;
                telegram.send(chatId, "Qu'est-ce que je change ? Par exemple : plus court, 6 répétitions, plutôt en côte, à vélo plutôt qu'à pied.");
            }
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

    static String preview(LocalDate day, Workout w, Thresholds t, String why, String alreadyPlanned) {
        int seconds = Draft.seconds(w, t);
        String length = seconds >= 3600 ? seconds / 3600 + "h" + String.format("%02d", seconds % 3600 / 60) : Math.round(seconds / 60.0) + " min";
        return "« " + w.name() + " » · " + sportName(w.sport()) + " · " + length + " · " + DAY.format(day) + "\n"
                + (why == null || why.isBlank() ? "" : why + "\n") + "\n"
                + WorkoutText.render(w) + "\n" + alreadyPlanned + "\n\nÇa te va ?";
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

    static String prompt(LocalDate day, String request, Thresholds t, String context, String previousDraft, String change) {
        String base = """
                Task: prescribe one workout for %s, as a coach who takes the initiative. Answer with JSON
                and nothing else. Do not compute watts or paces: express intensities as below, the service
                does the arithmetic.

                Athlete's thresholds: threshold power %s, threshold pace %s, critical swim speed %s.

                JSON shape:
                {"why": "one or two French sentences: the choice and what it rests on",
                 "workout": {"sport": "Ride" | "VirtualRide" | "Run" | "TrailRun" | "Swim",
                   "name": "short French title, no digits",
                   "sections": [{"title": "Warmup" | "Main Set" | "Cooldown" | "Drills",
                                 "repeats": 1,
                                 "steps": [{"seconds": 600, "metres": null, "kind": "...", "low": 0, "high": null,
                                            "cadence": null, "cue": ""}]}]}}

                kind, with low and high:
                - THRESHOLD_POWER_PERCENT: percent of threshold power (bike), e.g. 88 and 94
                - WATTS: absolute watts, only when the athlete gave watts
                - POWER_ZONE: zone 1 to 7 in low (bike)
                - HEART_RATE_ZONE: zone 1 to 7 in low (any sport)
                - THRESHOLD_PACE_OFFSET: seconds slower than threshold pace per km on foot, or than critical
                  swim speed per 100 m in the water; negative is faster. For a range, low is the faster end.
                - PACE: absolute seconds per km (run) or per 100 m (swim), only when the athlete gave a pace

                How to prescribe:
                - Whatever the athlete specified (sport, duration, repetitions, numbers) is kept exactly.
                  Everything left open, you decide: a complete, ready-to-ride workout, never a question
                  when a sensible choice exists. "cap" and "footing" mean running.
                - Duration when not given: the time available that day if known, otherwise easy 45-60 min,
                  quality sessions 50-70 min including warm-up and cool-down, long sessions 1h30-2h30.
                - Intensities by name. Bike, percent of threshold power: recovery 45-55, endurance 56-75,
                  tempo 76-87, sweet spot 88-94, threshold 95-105, VO2max 106-120, anaerobic 121-150.
                  Run, offsets from threshold pace: easy +50 to +70, steady +25 to +40, tempo +10 to +20,
                  threshold -5 to +5, VO2max -15 to -25. Swim, offsets from critical swim speed: recovery
                  +15 to +20, aerobic +8 to +12, tempo +3 to +6, threshold 0, VO2max -3 to -5.
                - Usual formats. VO2max: 4 to 6 x 3 to 4 min with equal or slightly shorter recovery
                  (bike: 5 x 4 min at 106-115 with 3-4 min easy; run: 5 x 3 min or 6 x 800 m with
                  jogged recovery). Threshold: 3 x 8-12 min or 2 x 15-20 min, recovery a third to half
                  of the work. Sweet spot: 2 x 15-20 min or 3 x 12-15 min. Easy run: steady in the easy
                  band, optionally 4-6 strides of 15-20 s near VO2max pace with full recovery at the end.
                  Swim: drills first, then the main set in metres. Always a warm-up of 10-15 min (or
                  200-400 m) and a cool-down of 5-10 min around hard work.
                - Calibrate on the context below: a red verdict for that day means easy only, whatever
                  was asked, and say so in "why"; orange trims volume, not intent; a hard session in the
                  last 48 hours shortens or softens a new hard one. Name the basis in "why" (verdict,
                  recent load, last hard session) and never invent a number the context does not hold.
                - A repeated block is one section with "repeats" greater than 1, holding the work step and
                  its recovery. seconds or metres, never both; swim sets in metres. cadence in rpm, bike
                  only, or null. cue: a few French words, no digits, no units, no symbols; empty is fine.
                - Only if the request is not about a workout at all, answer one line starting with
                  QUESTION: followed by a short question in French.

                """.formatted(day + " (" + DAY.format(day) + ")",
                t.thresholdPower() == null ? "unknown" : t.thresholdPower() + " W",
                t.thresholdPace() == null ? "unknown" : t.thresholdPace() / 60 + ":" + String.format("%02d", t.thresholdPace() % 60) + "/km",
                t.criticalSwimPace() == null ? "unknown" : t.criticalSwimPace() / 60 + ":" + String.format("%02d", t.criticalSwimPace() % 60) + "/100m");
        String out = base + PromptFence.data("race-radio.context", context) + "\n" + PromptFence.data("request", request);
        if (previousDraft != null) {
            out += "\nYou proposed this workout:\n" + PromptFence.data("race-radio.previous-draft", previousDraft)
                    + "\nThe athlete asks for this change. Apply it, keep the rest, and say in \"why\" what changed:\n"
                    + PromptFence.data("change", change);
        }
        return out;
    }
}
