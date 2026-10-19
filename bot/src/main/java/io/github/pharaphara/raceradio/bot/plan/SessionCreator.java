package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.archive.Archive;
import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.PromptFence;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.store.Conversations;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.plan.WeekCheck;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Workout;
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
import java.util.ArrayList;
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
 * Workouts on demand, outside any programme: one with {@code /seance demain cap facile}, a week with
 * {@code /semaine}.
 *
 * <p>The coach takes the initiative: a vague request gets complete, calibrated workouts, each with a
 * few words per step (heard through headphones and read on the watch during the effort) and a note
 * on how to do it, written above the steps in the calendar so it travels with the workout. The basis
 * is local: the morning verdict and this morning against the week, availability, objective, the last
 * two weeks and what is already planned. The model only translates into {@link Draft}s; arithmetic
 * and bounds are {@link Draft#build}'s, syntax {@link WorkoutText}'s, week rules {@link WeekCheck}'s,
 * and nothing is published before the athlete presses Publier.
 */
public final class SessionCreator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionCreator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUESTION = "QUESTION:";
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE d", Locale.FRANCE);
    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("lun", DayOfWeek.MONDAY), Map.entry("mar", DayOfWeek.TUESDAY), Map.entry("mer", DayOfWeek.WEDNESDAY),
            Map.entry("jeu", DayOfWeek.THURSDAY), Map.entry("ven", DayOfWeek.FRIDAY), Map.entry("sam", DayOfWeek.SATURDAY),
            Map.entry("dim", DayOfWeek.SUNDAY));
    private static final Pattern DAY_WORD = Pattern.compile(
            "(?i)^(aujourd'hui|aujourdhui|auj|demain|après-demain|apres-demain|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|lun|mar|mer|jeu|ven|sam|dim|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2})\\b\\s*");
    /** A day named anywhere in the request: full words only, a date first, so "marche" never reads as Tuesday. */
    private static final Pattern DAY_ANYWHERE = Pattern.compile(
            "(?i)(?<![\\p{L}\\d-])(\\d{4}-\\d{2}-\\d{2}|aujourd'hui|demain|après-demain|apres-demain|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?![\\p{L}\\d])");
    private static final Map<String, String> VERDICTS = Map.of("GREEN", "green, train as planned", "YELLOW", "yellow, lighter version",
            "RED", "red, rest or active recovery");
    /** Days of history given to the model to calibrate. */
    static final int RECENT_DAYS = 14;
    static final int WEEK = 7;

    /** One workout to publish, with its note. */
    public record Item(LocalDate day, Workout workout, String note) {
    }

    /**
     * A draft waiting for the athlete's decision. Lost on restart, which only means asking again.
     *
     * @param answerJson  the draft as the model wrote it, to amend it without starting over
     */
    public record Pending(boolean week, LocalDate start, List<Item> items, String why, String request, String answerJson) {
    }

    /** What a request gave: workouts to show, or only a message (help, question, refusal). */
    public record Prepared(String message, Pending pending) {
    }

    private final CoachModel model;
    private final Planner planner;
    private final IntervalsWriter writer;
    private final Plans plans;
    private final DecisionLog decisions;
    private final Conversations memory;
    private final Archive archive;
    private final TelegramClient telegram;
    private final long chatId;
    private final Clock clock;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Long, List<Long>> publishedWeeks = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    /** The draft the athlete pressed Modifier on: their next plain message is the change. */
    private volatile Long amending;
    private io.github.pharaphara.raceradio.bot.commands.QuickReplies replies = new io.github.pharaphara.raceradio.bot.commands.QuickReplies();

    /** The store behind the buttons that speak for the athlete. */
    public SessionCreator withReplies(io.github.pharaphara.raceradio.bot.commands.QuickReplies replies) {
        this.replies = replies;
        return this;
    }

    public SessionCreator(CoachModel model, Planner planner, IntervalsWriter writer, Plans plans, DecisionLog decisions,
                          Conversations memory, Archive archive, TelegramClient telegram, long chatId, Clock clock) {
        this.model = model;
        this.planner = planner;
        this.writer = writer;
        this.plans = plans;
        this.decisions = decisions;
        this.memory = memory;
        this.archive = archive;
        this.telegram = telegram;
        this.chatId = chatId;
        this.clock = clock;
    }

    /** Handles {@code /seance ...}. */
    public void draft(String text) {
        amending = null;
        show(prepare(text));
    }

    /** Handles {@code /semaine ...}. */
    public void week(String text) {
        amending = null;
        show(prepareWeek(text));
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
            telegram.send(chatId, "Ce brouillon n'existe plus. Redemande avec /seance ou /semaine.");
            return;
        }
        Prepared next = p.week() ? draftWeek(p.start(), p.request(), p.answerJson(), change)
                : draftOne(p.start(), p.request(), p.answerJson(), change);
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
        Map<String, String> first = new LinkedHashMap<>();
        first.put(p.pending().week() ? "Publier la semaine" : "Publier", "pub:" + id);
        if (p.pending().week()) {
            first.put("Voir le détail", "det:" + id);
        }
        Map<String, String> second = new LinkedHashMap<>();
        second.put("Modifier", "edit:" + id);
        second.put("Annuler", "drop:" + id);
        telegram.sendWithButtonRows(chatId, p.message(), List.of(first, second));
    }

    /** Reads a {@code /seance} request and drafts the workout, without sending or publishing anything. */
    public Prepared prepare(String text) {
        String rest = text.replaceFirst("(?i)^/s[eé]ance(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            return new Prepared("""
                    Dis-moi quoi et quand, je m'occupe du reste :
                    /seance demain cap facile
                    /seance jeudi une VO2 à vélo
                    /seance samedi sortie longue 2h30
                    /seance demain vélo 3x10min à 280W
                    Pour toute une semaine : /semaine""", null);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate day;
        try {
            DayRequest d = dayRequest(rest, today);
            day = d.day();
            rest = d.rest();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return new Prepared("Je n'ai pas compris le jour. Écris aujourd'hui, demain, un jour de la semaine ou une date (2026-11-03).", null);
        }
        if (day.isBefore(today)) {
            return new Prepared("Ce jour est passé.", null);
        }
        if (rest.isEmpty()) {
            return new Prepared("Et quelle séance ? Par exemple : /seance demain cap facile", null);
        }
        return draftOne(day, rest, null, null);
    }

    /**
     * Reads a {@code /semaine} request: the seven days from next Monday, or from the day named
     * first ("/semaine demain", "/semaine mercredi"), and an optional wish.
     */
    public Prepared prepareWeek(String text) {
        String rest = text.replaceFirst("(?i)^/semaine(@\\S+)?\\s*", "").strip();
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        try {
            Matcher m = DAY_WORD.matcher(rest);
            if (m.find()) {
                start = day(m.group(1), today);
                rest = rest.substring(m.end()).strip();
            }
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return new Prepared("Je n'ai pas compris le jour de départ. Exemple : /semaine demain, ou /semaine seul pour lundi prochain.", null);
        }
        if (start.isBefore(today)) {
            return new Prepared("Ce jour est passé.", null);
        }
        return draftWeek(start, rest.isEmpty() ? "une semaine équilibrée, à toi de choisir" : rest, null, null);
    }

    private Prepared draftOne(LocalDate day, String request, String previous, String change) {
        Thresholds t = planner.thresholds();
        try {
            JsonNode root = ask(prompt(day, request, t, context(day, 3), previous, change));
            if (root == null) {
                return new Prepared(lastQuestion, null);
            }
            JsonNode workout = root.has("workout") ? root.path("workout") : root;
            Workout w = buildable(JSON.treeToValue(workout, Draft.class).build(t));
            String why = text(root, "why");
            String note = WorkoutText.note(text(root, "note"));
            Pending p = new Pending(false, day, List.of(new Item(day, w, note)), why, request, root.toString());
            return new Prepared(preview(day, w, t, why, note, alreadyPlanned(day)), p);
        } catch (CoachModel.BudgetExhausted e) {
            return new Prepared("Le budget du modèle est atteint ce mois-ci : je ne peux pas préparer de séance.", null);
        } catch (IllegalArgumentException e) {
            return new Prepared("Je ne peux pas construire cette séance : " + e.getMessage() + "\nReformule, ou précise les intensités.", null);
        } catch (RuntimeException e) {
            LOG.warn("drafting a workout failed: {}", e.toString());
            return new Prepared("Je n'ai pas réussi à préparer cette séance. Reformule-la plus simplement.", null);
        }
    }

    private Prepared draftWeek(LocalDate start, String request, String previous, String change) {
        Thresholds t = planner.thresholds();
        try {
            String context = context(start, WEEK);
            JsonNode root = ask(weekPrompt(start, request, t, context, previous, change));
            if (root == null) {
                return new Prepared(lastQuestion, null);
            }
            List<Item> items = weekItems(root, t);
            List<String> problems = WeekCheck.problems(start, WEEK, items.stream().map(i -> new WeekCheck.Planned(i.day(), i.workout())).toList(), t);
            if (!problems.isEmpty()) {
                // One second try, told exactly what broke; the program, not the model, keeps the rules.
                root = ask(weekPrompt(start, request, t, context, root.toString(),
                        "The week breaks these rules, fix them and keep the rest: " + String.join("; ", problems)));
                if (root == null) {
                    return new Prepared(lastQuestion, null);
                }
                items = weekItems(root, t);
                problems = WeekCheck.problems(start, WEEK, items.stream().map(i -> new WeekCheck.Planned(i.day(), i.workout())).toList(), t);
                if (!problems.isEmpty()) {
                    return new Prepared("Je n'arrive pas à te proposer une semaine qui respecte les règles (" + String.join(" ; ", problems)
                            + "). Reformule ta demande, par exemple avec moins de séances dures.", null);
                }
            }
            String why = text(root, "why");
            Pending p = new Pending(true, start, items, why, request, root.toString());
            return new Prepared(weekPreview(start, items, t, why), p);
        } catch (CoachModel.BudgetExhausted e) {
            return new Prepared("Le budget du modèle est atteint ce mois-ci : je ne peux pas préparer de semaine.", null);
        } catch (IllegalArgumentException e) {
            return new Prepared("Je ne peux pas construire cette semaine : " + e.getMessage() + "\nReformule ta demande.", null);
        } catch (RuntimeException e) {
            LOG.warn("drafting a week failed: {}", e.toString());
            return new Prepared("Je n'ai pas réussi à préparer cette semaine. Reformule-la plus simplement.", null);
        }
    }

    private volatile String lastQuestion = "";

    /** The model's answer as JSON, or null when it asked a question instead (kept in lastQuestion). */
    private JsonNode ask(String prompt) {
        ModelReply r = model.ask(Purpose.SESSION, "low", 6_000, prompt);
        String answer = r.text().strip();
        if (r.refused() || answer.isEmpty()) {
            throw new IllegalStateException("no answer");
        }
        if (answer.startsWith(QUESTION)) {
            lastQuestion = answer.substring(QUESTION.length()).strip();
            return null;
        }
        return JSON.readTree(json(answer));
    }

    static List<Item> weekItems(JsonNode root, Thresholds t) {
        List<Item> items = new ArrayList<>();
        for (JsonNode d : root.path("days")) {
            LocalDate day = LocalDate.parse(d.path("day").asString());
            Workout w = buildable(JSON.treeToValue(d.path("workout"), Draft.class).build(t));
            items.add(new Item(day, w, WorkoutText.note(text(d, "note"))));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("the week holds no workout");
        }
        items.sort(java.util.Comparator.comparing(Item::day));
        return items;
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).isString() ? n.path(field).asString().strip() : "";
    }

    /** What a coach would look at before prescribing, all of it local. */
    String context(LocalDate from, int days) {
        StringBuilder b = new StringBuilder();
        LocalDate today = LocalDate.now(clock);
        decisions.latestOf("MORNING").filter(d -> d.day().equals(today.toString())).ifPresent(d ->
                b.append("Morning verdict today (").append(today).append("): ").append(VERDICTS.getOrDefault(d.verdict(), d.verdict())).append('\n'));
        archive.morning().ifPresent(m -> b.append(m).append('\n'));
        Map<DayOfWeek, Integer> available = plans.availability();
        if (!available.isEmpty()) {
            b.append("Time available per weekday (minutes): ").append(available).append('\n');
        }
        plans.objectives().forEach(o -> b.append("Objective: ").append(o.objective()).append('\n'));
        b.append("Sports in season: ").append(plans.sports()).append('\n');
        try {
            List<String> recent = archive.recent(RECENT_DAYS);
            b.append("Sessions of the last ").append(RECENT_DAYS).append(" days:\n")
                    .append(recent.isEmpty() ? "none recorded\n" : String.join("\n", recent) + "\n");
            List<String> planned = archive.planned(today, (int) (from.toEpochDay() - today.toEpochDay()) + days + 2);
            b.append("Already on the calendar from today:\n").append(planned.isEmpty() ? "nothing\n" : String.join("\n", planned) + "\n");
        } catch (RuntimeException e) {
            b.append("History: unavailable\n");
        }
        List<String> notes = memory.activeNotes().stream().map(Conversations.Note::text).toList();
        if (!notes.isEmpty()) {
            b.append("The athlete's notes:\n").append(String.join("\n", notes)).append('\n');
        }
        return b.toString();
    }

    /** True for the buttons this class put under its messages. */
    public boolean handles(String data) {
        return data.matches("(pub|drop|del|edit|det|delw):\\d+");
    }

    public void callback(String data) {
        String[] parts = data.split(":", 2);
        long id = Long.parseLong(parts[1]);
        switch (parts[0]) {
            case "drop" -> {
                amending = null;
                telegram.send(chatId, pending.remove(id) == null ? "Déjà traité." : "Abandonné, rien n'a été publié.");
            }
            case "pub" -> {
                amending = null;
                publish(id);
            }
            case "det" -> {
                Pending p = pending.get(id);
                telegram.send(chatId, p == null ? "Ce brouillon n'existe plus." : detail(p, planner.thresholds()));
            }
            case "edit" -> {
                if (!pending.containsKey(id)) {
                    telegram.send(chatId, "Ce brouillon n'existe plus. Redemande avec /seance ou /semaine.");
                    return;
                }
                amending = id;
                telegram.send(chatId, "Qu'est-ce que je change ? Par exemple : plus court, 6 répétitions, plutôt en côte, "
                        + "à vélo plutôt qu'à pied, pas de séance mercredi.");
            }
            case "del" -> {
                try {
                    writer.deleteOwnEvent(id);
                    refreshCalendar();
                    telegram.send(chatId, "Séance retirée du calendrier.");
                } catch (RuntimeException e) {
                    telegram.send(chatId, "Je n'ai pas pu la retirer : " + e.getMessage());
                }
            }
            case "delw" -> {
                List<Long> events = publishedWeeks.remove(id);
                if (events == null) {
                    telegram.send(chatId, "Cette semaine n'est plus en mémoire : retire les séances une à une dans Intervals.icu.");
                    return;
                }
                int done = 0;
                for (long e : events) {
                    try {
                        writer.deleteOwnEvent(e);
                        done++;
                    } catch (RuntimeException ex) {
                        LOG.warn("removing event {} failed: {}", e, ex.getMessage());
                    }
                }
                refreshCalendar();
                telegram.send(chatId, done + " séance" + (done > 1 ? "s retirées" : " retirée") + " du calendrier.");
            }
            default -> { }
        }
    }

    private void publish(long id) {
        Pending p = pending.remove(id);
        if (p == null) {
            telegram.send(chatId, "Ce brouillon n'existe plus (déjà publié, abandonné, ou le service a redémarré). Redemande-le.");
            return;
        }
        List<Long> created = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Item i : p.items()) {
            try {
                writer.publishWorkout(i.day(), i.workout(), i.note()).ifPresent(e -> created.add(e.id()));
            } catch (IntervalsWriter.WorkoutNotUnderstood e) {
                problems.add(SHORT.format(i.day()) + " « " + i.workout().name() + " » mal comprise par Intervals.icu, retirée");
            } catch (RuntimeException e) {
                problems.add(SHORT.format(i.day()) + " « " + i.workout().name() + " » : " + e.getMessage());
            }
        }
        if (!writer.allows(IntervalsWriter.Write.WORKOUTS)) {
            telegram.send(chatId, "Mode sec : rien n'a été publié dans Intervals.icu.");
            return;
        }
        refreshCalendar();
        String trouble = problems.isEmpty() ? "" : "\nProblèmes : " + String.join(" ; ", problems);
        if (!p.week()) {
            if (created.isEmpty()) {
                telegram.send(chatId, "La publication a échoué." + trouble);
                return;
            }
            Item i = p.items().getFirst();
            List<Map<String, String>> rows = new ArrayList<>(replies.rows(List.of("Comment exécuter ma séance du " + SHORT.format(i.day()) + " ?")));
            rows.add(Map.of("Retirer du calendrier", "del:" + created.getFirst()));
            telegram.sendWithButtonRows(chatId, "« " + i.workout().name() + " » publiée pour " + DAY.format(i.day())
                    + ", avec sa note. Elle partira vers ta montre et MyWhoosh à la prochaine synchronisation." + trouble, rows);
            return;
        }
        long batch = ids.incrementAndGet();
        publishedWeeks.put(batch, List.copyOf(created));
        telegram.sendWithButtons(chatId, created.size() + " séance" + (created.size() > 1 ? "s publiées" : " publiée")
                + " à partir du " + DAY.format(p.start()) + ", chacune avec sa note." + trouble,
                Map.of("Retirer la semaine", "delw:" + batch));
    }

    private void refreshCalendar() {
        try {
            archive.syncEvents();
        } catch (RuntimeException e) {
            LOG.warn("refreshing the calendar cache failed: {}", e.getMessage());
        }
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
            List<String> names = archive.planned(day, 1);
            return names.isEmpty() ? "" : "\nDéjà prévu ce jour-là : " + String.join(", ", names.stream().map(n -> n.substring(11)).toList())
                    + ". Je l'ajoute à côté, sans rien remplacer.";
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String preview(LocalDate day, Workout w, Thresholds t, String why, String note, String alreadyPlanned) {
        StringBuilder b = new StringBuilder("« ").append(w.name()).append(" » · ").append(WorkoutFormat.sport(w.sport()))
                .append(" · ").append(WorkoutFormat.total(w, t)).append(" · ").append(DAY.format(day)).append('\n');
        if (why != null && !why.isBlank()) {
            b.append(why).append('\n');
        }
        b.append('\n');
        WorkoutFormat.lines(w, t).forEach(l -> b.append(l).append('\n'));
        if (note != null && !note.isBlank()) {
            b.append("\nNote d'exécution : ").append(note).append('\n');
        }
        return b.append(alreadyPlanned).append("\n\nÇa te va ?").toString();
    }

    static String weekPreview(LocalDate start, List<Item> items, Thresholds t, String why) {
        int total = items.stream().mapToInt(i -> Draft.seconds(i.workout(), t)).sum();
        long hard = items.stream().filter(i -> WeekCheck.hard(i.workout(), t)).map(Item::day).distinct().count();
        StringBuilder b = new StringBuilder("Ta semaine à partir du ").append(DAY.format(start)).append(" · ")
                .append(WorkoutFormat.duration(total)).append(" · ").append(hard).append(hard > 1 ? " séances dures" : " séance dure").append('\n');
        if (why != null && !why.isBlank()) {
            b.append(why).append('\n');
        }
        b.append('\n');
        for (int d = 0; d < WEEK; d++) {
            LocalDate day = start.plusDays(d);
            List<Item> today = items.stream().filter(i -> i.day().equals(day)).toList();
            if (today.isEmpty()) {
                b.append(SHORT.format(day)).append(" · repos\n");
            }
            for (Item i : today) {
                b.append(SHORT.format(day)).append(" · ").append(i.workout().name()).append(" · ").append(WorkoutFormat.total(i.workout(), t))
                        .append(WeekCheck.hard(i.workout(), t) ? " · dure" : "").append('\n');
            }
        }
        return b.append("\nÇa te va ?").toString();
    }

    /** Every workout of a draft, in full, with its note. */
    public String detail(Pending p) {
        return detail(p, planner.thresholds());
    }

    static String detail(Pending p, Thresholds t) {
        StringBuilder b = new StringBuilder();
        for (Item i : p.items()) {
            b.append(DAY.format(i.day())).append(" · « ").append(i.workout().name()).append(" » · ").append(WorkoutFormat.sport(i.workout().sport()))
                    .append(" · ").append(WorkoutFormat.total(i.workout(), t)).append('\n');
            WorkoutFormat.lines(i.workout(), t).forEach(l -> b.append(l).append('\n'));
            if (!i.note().isBlank()) {
                b.append("Note : ").append(i.note()).append('\n');
            }
            b.append('\n');
        }
        return b.toString().strip();
    }

    record DayRequest(LocalDate day, String rest) {
    }

    /**
     * The day of a request and what is left of it: a day word first ("demain vélo 1h"), else a full
     * day name or a date anywhere ("une séance de seuil jeudi"), else today.
     */
    static DayRequest dayRequest(String text, LocalDate today) {
        Matcher m = DAY_WORD.matcher(text);
        if (m.find()) {
            return new DayRequest(day(m.group(1), today), text.substring(m.end()).strip());
        }
        Matcher anywhere = DAY_ANYWHERE.matcher(text);
        if (anywhere.find()) {
            String rest = (text.substring(0, anywhere.start()) + " " + text.substring(anywhere.end())).replaceAll("\\s+", " ").strip();
            return new DayRequest(day(anywhere.group(1), today), rest);
        }
        return new DayRequest(today, text);
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

    /** What both prompts share: units, how to write a workout and its note, how to calibrate. */
    static String rules(Thresholds t) {
        return """
                Athlete's thresholds: threshold power %s, threshold pace %s, critical swim speed %s.
                Do not compute watts or paces: express intensities as below, the service does the arithmetic.

                A workout is JSON:
                {"sport": "Ride" | "VirtualRide" | "Run" | "TrailRun" | "Swim",
                 "name": "readable French title with the key numbers, e.g. \\"Course facile · 45 min\\",
                          \\"Vélo seuil · 3 × 10 min à 270 W\\", \\"Natation technique · 1800 m\\"",
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

                Cues: the athlete hears each step change through headphones and glances at the watch, so a
                step's cue is the one thing to do during it, in two to five French words ("relâché, cadence
                haute", "régulier", "marche en côte", "souple"). No digits, units, percent sign, colon or
                slash; at most 40 characters.

                Note: two to four French sentences the athlete reads before the session: what it is for, in
                plain words; how to do it (relaxation, cadence rather than force, on trail walk the climbs and
                forget the pace); how to tell it went right ("finir la dernière en sentant qu'une de plus
                passait, pas deux"); a target effort out of 10; for sessions over 90 minutes, carbohydrate per
                hour and drinking, from the nutrition rules and the athlete's notes. Never explanations the
                knowledge base does not hold (no toxins, no lactate to flush, no invented recovery delays).

                How to prescribe:
                - Whatever the athlete specified (sport, duration, repetitions, numbers) is kept exactly; when
                  they give numbers, use WATTS or PACE. Everything left open, you decide: complete, ready to do,
                  never a question when a sensible choice exists. "cap" and "footing" mean running.
                - Duration when not given: the time available that day if known, otherwise easy 45-60 min,
                  quality sessions 50-70 min including warm-up and cool-down, long sessions 1h30-2h30.
                - Intensities by name. Bike, percent of threshold power: recovery 45-55, endurance 56-75,
                  tempo 76-87, sweet spot 88-94, threshold 95-105, VO2max 106-120, anaerobic 121-150.
                  Run, offsets from threshold pace: easy +50 to +70, steady +25 to +40, tempo +10 to +20,
                  threshold -5 to +5, VO2max -15 to -25. Swim, offsets from critical swim speed: recovery
                  +15 to +20, aerobic +8 to +12, tempo +3 to +6, threshold 0, VO2max -3 to -5.
                - Usual formats. VO2max: 4 to 6 x 3 to 4 min with equal or slightly shorter recovery (bike:
                  5 x 4 min at 106-115; run: 5 x 3 min or 6 x 800 m with jogged recovery). Threshold: 3 x 8-12
                  min or 2 x 15-20 min, recovery a third to half of the work. Sweet spot: 2 x 15-20 min or
                  3 x 12-15 min. Easy run: steady in the easy band, optionally 4-6 strides of 15-20 s near
                  VO2max pace with full recovery at the end. Before hard work, a warm-up of 10-15 min ending
                  with two or three short pick-ups; a cool-down of 5-10 min. Swim: drills first, sets in metres.
                - Calibrate on the context: a red verdict today means easy only today, whatever was asked, and
                  say so; orange trims volume, not intent; a hard session in the last 48 hours, or one already
                  on the calendar nearby, shortens or moves a new hard one. Name the basis in "why" (verdict,
                  this morning against the week, recent load, the last hard session, recent effort ratings)
                  and never invent a number the context does not hold.
                - A repeated block is one section with "repeats" greater than 1, holding the work step and its
                  recovery. seconds or metres, never both; swim sets in metres. cadence in rpm, bike only, or
                  null.
                - Only if the request is not about training at all, answer one line starting with QUESTION:
                  followed by a short question in French.
                """.formatted(t.thresholdPower() == null ? "unknown" : t.thresholdPower() + " W",
                t.thresholdPace() == null ? "unknown" : t.thresholdPace() / 60 + ":" + String.format("%02d", t.thresholdPace() % 60) + "/km",
                t.criticalSwimPace() == null ? "unknown" : t.criticalSwimPace() / 60 + ":" + String.format("%02d", t.criticalSwimPace() % 60) + "/100m");
    }

    static String prompt(LocalDate day, String request, Thresholds t, String context, String previous, String change) {
        String base = """
                Task: prescribe one workout for %s, as a coach who takes the initiative. Answer with JSON and
                nothing else:
                {"why": "one or two French sentences: the choice and what it rests on",
                 "note": "the note, as described below",
                 "workout": { a workout }}

                """.formatted(day + " (" + DAY.format(day) + ")") + rules(t);
        return withRequest(base, context, request, previous, change);
    }

    static String weekPrompt(LocalDate start, String request, Thresholds t, String context, String previous, String change) {
        String base = """
                Task: prescribe the athlete's week, the seven days from %s to %s, as a coach who takes the
                initiative. Answer with JSON and nothing else:
                {"why": "two or three French sentences: the week's intent and what it rests on",
                 "days": [{"day": "YYYY-MM-DD", "note": "the note", "workout": { a workout }}]}
                A day without anything is simply absent from "days".

                Week rules, checked by the program, which refuses a week that breaks them:
                - at most two hard sessions (threshold or above for five minutes or more), never on two
                  consecutive days, and most of the week easy;
                - one longer session, on the day with the most time;
                - at least one day with nothing; at most two sessions on one day (a bike then run brick);
                - only sports in season; the time available on each weekday when it is known;
                - total time close to the average week of the last two weeks unless the athlete asks
                  otherwise, and a lighter week after three heavier ones;
                - workouts already on the calendar count: do not stack a hard day next to one.

                """.formatted(start, start.plusDays(WEEK - 1)) + rules(t);
        return withRequest(base, context, request, previous, change);
    }

    private static String withRequest(String base, String context, String request, String previous, String change) {
        String out = base + "\n" + PromptFence.data("race-radio.context", context) + "\n" + PromptFence.data("request", request);
        if (previous != null) {
            out += "\nYou proposed this:\n" + PromptFence.data("race-radio.previous-draft", previous)
                    + "\nApply this change, keep the rest, and say in \"why\" what changed:\n" + PromptFence.data("change", change);
        }
        return out;
    }
}
