package io.github.pharaphara.raceradio.bot.chat;

import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.PromptFence;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.store.Conversations;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.core.readiness.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Free conversation. The coach answers with what it knows today and nothing more: this morning's
 * verdict, the latest session review, the athlete's notes and the last few turns. It cannot change
 * the plan or a verdict from here; it can explain them, and point to the command that would.
 */
public final class Conversation {

    private static final Logger LOG = LoggerFactory.getLogger(Conversation.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int TURNS = 10;
    /** Mornings and sessions given with every message; older ones are looked up on demand. */
    static final int RECENT_DAYS = 14;

    private final CoachModel model;
    private final MorningCheck morning;
    private final DecisionLog decisions;
    private final Conversations memory;
    private final Clock clock;
    private final History history;

    public Conversation(CoachModel model, MorningCheck morning, DecisionLog decisions, Conversations memory, Clock clock) {
        this(model, morning, decisions, memory, clock, History.NONE);
    }

    public Conversation(CoachModel model, MorningCheck morning, DecisionLog decisions, Conversations memory, Clock clock,
                        History history) {
        this.history = history;
        this.model = model;
        this.morning = morning;
        this.decisions = decisions;
        this.memory = memory;
        this.clock = clock;
    }

    /** Something the program must do for the athlete: open a /seance or /semaine draft. */
    public record Action(String kind, String request) {
    }

    /**
     * The coach's answer: text to send (null when the program already answered, a trend for
     * instance), up to three follow-ups to offer as buttons, and an action to carry out, if any.
     */
    public record Reply(String text, List<String> suggestions, Action action) {

        static Reply of(String text) {
            return new Reply(text, List.of(), null);
        }
    }

    public String answer(String athleteMessage) {
        return reply(athleteMessage).text();
    }

    /** Whether the model answers as JSON with references to the context, for weaker models. */
    private boolean structured;
    /** The context of the last prompt, where an answer's references are looked up. */
    private volatile tools.jackson.databind.JsonNode lastContext;


    /** The athlete's sports: recommendations stay within them unless the athlete brings up another. */
    private java.util.function.Supplier<java.util.Set<String>> sports = java.util.Set::of;

    public Conversation withSports(java.util.function.Supplier<java.util.Set<String>> sports) {
        this.sports = sports;
        return this;
    }

    public Conversation structured(boolean on) {
        this.structured = on;
        return this;
    }

    public Reply reply(String athleteMessage) {
        String prompt = prompt(athleteMessage);
        memory.add(clock.instant(), "athlete", athleteMessage);
        String text;
        List<String> suggestions = List.of();
        try {
            ModelReply r = model.ask(Purpose.CHAT, "low", 2_000, prompt);
            String raw = r.refused() || r.text().isBlank()
                    ? "Je ne peux pas répondre à ça. Les commandes restent disponibles : /aide."
                    : structured ? fromJson(r.text()) : r.text();
            int cut = raw.toUpperCase(java.util.Locale.ROOT).lastIndexOf(SUGGESTIONS);
            text = (cut < 0 ? raw : raw.substring(0, cut)).strip();
            if (cut >= 0) {
                suggestions = raw.substring(cut + SUGGESTIONS.length()).lines().map(String::strip)
                        .filter(l -> l.startsWith("-") || l.startsWith("•"))
                        .map(l -> l.substring(1).strip().replaceAll("^[\"«»“”']+|[\"«»“”']+$", "").strip())
                        .filter(l -> !l.isEmpty()).limit(3).toList();
            }
            Optional<Action> action = action(text);
            if (action.isPresent()) {
                memory.add(clock.instant(), "coach", "[" + text + "]");
                return new Reply(null, List.of(), action.get());
            }
            Optional<String> looked = lookup(text);
            if (looked.isPresent()) {
                memory.add(clock.instant(), "coach", "[" + text + "] " + looked.get());
                return Reply.of(looked.get().isEmpty() ? null : looked.get());
            }
        } catch (CoachModel.BudgetExhausted e) {
            text = "Le budget du modèle est atteint ce mois-ci : je ne peux plus converser, mais /forme, /aujourdhui et /pourquoi fonctionnent.";
        } catch (RuntimeException e) {
            LOG.warn("conversation failed: {}", e.getMessage());
            text = "Je n'arrive pas à joindre le modèle pour l'instant. Réessaie dans un moment.";
        }
        memory.add(clock.instant(), "coach", text);
        return new Reply(text, suggestions, null);
    }

    static final String SUGGESTIONS = "SUGGESTIONS:";

    /**
     * A structured answer turned back into the plain form the rest of this class reads: the
     * delegation line alone, or the answer with its figures filled in, then the suggestions.
     */
    String fromJson(String answer) {
        return fromJson(answer, lastContext == null ? JSON.createObjectNode() : lastContext);
    }

    public static String fromJson(String answer, tools.jackson.databind.JsonNode context) {
        int from = answer.indexOf('{');
        int to = answer.lastIndexOf('}');
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("the answer is not JSON");
        }
        tools.jackson.databind.JsonNode root = JSON.readTree(answer.substring(from, to + 1));
        String delegate = root.path("delegate").isString() ? root.path("delegate").asString().strip() : "";
        if (!delegate.isEmpty()) {
            return delegate;
        }
        List<String> problems = new java.util.ArrayList<>();
        String text = io.github.pharaphara.raceradio.bot.review.StructuredReview.fill(
                root.path("reply").asString(""), context, problems,
                io.github.pharaphara.raceradio.bot.llm.ReplyGuard.numbers(context.toString()));
        if (text.isBlank()) {
            problems.add("missing reply");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        StringBuilder b = new StringBuilder(text).append("\n").append(SUGGESTIONS);
        for (tools.jackson.databind.JsonNode s : root.path("suggestions")) {
            b.append("\n- ").append(s.asString(""));
        }
        return b.toString();
    }

    private static final java.util.regex.Pattern CREATE = java.util.regex.Pattern.compile("^(SEANCE|SEMAINE|DEPLACER|MODIFIER)\\b\\s*(.*)$", java.util.regex.Pattern.DOTALL);

    /** A reply that asks the program to open a draft rather than to answer. */
    static Optional<Action> action(String reply) {
        java.util.regex.Matcher m = CREATE.matcher(reply.strip());
        return m.matches() ? Optional.of(new Action(m.group(1), m.group(2).strip())) : Optional.empty();
    }

    private static final java.util.regex.Pattern DAY_LOOKUP = java.util.regex.Pattern.compile("^JOUR (\\d{4}-\\d{2}-\\d{2})$");
    private static final java.util.regex.Pattern TREND_LOOKUP = java.util.regex.Pattern.compile("^TENDANCE (\\w+) (\\d{1,2})$");
    private static final java.util.regex.Pattern ACTIVITY_LOOKUP = java.util.regex.Pattern.compile("^ACTIVITE (\\d{4}-\\d{2}-\\d{2})$");
    private static final java.util.regex.Pattern COMPARE_LOOKUP = java.util.regex.Pattern.compile(
            "^COMPARE (\\d{4}-\\d{2}-\\d{2}) (\\d{4}-\\d{2}-\\d{2})$");

    /**
     * When the model handed the question to the program, the program answers it from the archive.
     * Empty when the reply is an ordinary answer; an empty string when the answer was already sent
     * (a trend goes out as a chart and its text).
     */
    Optional<String> lookup(String reply) {
        java.util.regex.Matcher d = DAY_LOOKUP.matcher(reply);
        if (d.matches()) {
            java.time.LocalDate day = java.time.LocalDate.parse(d.group(1));
            return Optional.of(history.day(day).orElse("Je n'ai rien dans mon archive pour le " + day + "."));
        }
        java.util.regex.Matcher a = ACTIVITY_LOOKUP.matcher(reply);
        if (a.matches()) {
            java.time.LocalDate day = java.time.LocalDate.parse(a.group(1));
            return Optional.of(history.activity(day).orElse("Aucune séance dans mon archive le " + day + "."));
        }
        java.util.regex.Matcher c = COMPARE_LOOKUP.matcher(reply);
        if (c.matches()) {
            java.time.LocalDate one = java.time.LocalDate.parse(c.group(1));
            java.time.LocalDate two = java.time.LocalDate.parse(c.group(2));
            return Optional.of(history.compare(one, two).orElse("Il me manque une des deux séances dans mon archive."));
        }
        java.util.regex.Matcher t = TREND_LOOKUP.matcher(reply);
        if (t.matches()) {
            int months = Math.clamp(Integer.parseInt(t.group(2)), 2, 36);
            return Optional.of(history.trend(t.group(1), months) ? "" : "Je ne sais pas suivre cette mesure. /tendance donne la liste.");
        }
        return Optional.empty();
    }

    String prompt(String athleteMessage) {
        Map<String, Object> context = new LinkedHashMap<>();
        String today = java.time.LocalDate.now(clock).toString();
        Optional<DecisionLog.Decision> morningCall = decisions.latestOf("MORNING").filter(d -> d.day().equals(today));
        if (morningCall.isPresent()) {
            // This morning's verdict as it was recorded: no new call to compute it again.
            context.put("readinessToday", Map.of("verdict", morningCall.get().verdict(), "evidence", morningCall.get().evidenceJson()));
        } else {
            try {
                Readiness r = morning.assess();
                context.put("readinessToday", r);
            } catch (RuntimeException e) {
                context.put("readinessToday", "unavailable: " + e.getMessage());
            }
        }
        if (structured && context.get("readinessToday") instanceof Map<?, ?> m && m.get("evidence") instanceof String ev) {
            context.put("readinessToday", Map.of("verdict", m.get("verdict"), "evidence", parsed(ev)));
        }
        context.put("lastMornings", history.recentDays(RECENT_DAYS));
        context.put("lastSessions", history.recentSessions(RECENT_DAYS));
        decisions.latestOf("REVIEW").ifPresent(d -> context.put("latestSessionReview", Map.of("day", d.day(), "message",
                d.message() == null ? "" : d.message(), "facts", structured ? parsed(d.evidenceJson())
                        : d.evidenceJson().length() > 8_000 ? d.evidenceJson().substring(0, 8_000) : d.evidenceJson())));
        context.put("plannedNextDays", history.plannedDetail(3));
        if (!sports.get().isEmpty()) {
            context.put("sportsPractised", io.github.pharaphara.raceradio.bot.plan.Sports.french(sports.get()));
        }
        List<Conversations.Note> notes = memory.activeNotes();
        context.put("athleteNotes", notes.stream().map(n -> n.at().substring(0, 10) + " " + n.text()).toList());
        List<Conversations.Turn> turns = memory.last(TURNS);
        StringBuilder history = new StringBuilder();
        for (Conversations.Turn t : turns) {
            history.append(t.at(), 0, 16).append(' ').append(t.role()).append(": ").append(t.text()).append('\n');
        }
        if (structured) {
            lastContext = JSON.valueToTree(context);
        }
        String text = """
                Task: answer the athlete's message below, in French, for Telegram.
                Be brief: one to three sentences unless they ask for more. Use only the data given.
                You cannot change a verdict from here. To report how they feel there is
                /ressenti, to keep a fact /note, to redo the morning call /aujourdhui. If they report pain that
                persists, or a warning sign, recommend a health professional, once and plainly.
                Recommend sessions only in sportsPractised, unless the athlete brings up another sport.
                Asked how to do a planned session (plannedNextDays), answer as a briefing: what it is for,
                its structure with the durations and targets given, two or three cues, how to tell it went
                right, a target effort out of 10, fuelling for sessions over 90 minutes; bold short titles and
                lines starting with "• " are allowed. Asked for more detail on the latest review, go deeper
                into its facts.
                Some questions are answered by the program, not by you: then reply with exactly one line and
                nothing else, and the program will answer with the real figures or open a draft:
                JOUR YYYY-MM-DD  wellness and sessions of one day older than the %d days in the context
                ACTIVITE YYYY-MM-DD  the figures of one session (moving average, best efforts, heart rate)
                COMPARE YYYY-MM-DD YYYY-MM-DD  two sessions side by side
                TENDANCE <key> <months>  a figure over time, months from 2 to 36, key among:
                %s
                SEANCE <the request in the athlete's words, with the day>  to propose one workout
                SEMAINE <the request, or nothing>  to propose a week
                DEPLACER YYYY-MM-DD YYYY-MM-DD <sport or nothing>  to move a planned workout from the first day
                to the second (sport only when two workouts share the day)
                MODIFIER YYYY-MM-DD <sport or nothing> <the change, in the athlete's words>  to change a planned
                workout: shorter, easier, another format; the program shows the new version before publishing
                SEANCE and SEMAINE only open a draft the athlete then publishes or not: use them whenever the
                athlete asks for a workout or a week, or accepts one you suggested. Use DEPLACER and MODIFIER
                whenever they ask to move, shift, shorten, lighten or otherwise change a planned workout.
                Otherwise, after your answer, add a line SUGGESTIONS: and three lines starting with "- ", each
                a short message the athlete is likely to send next, in French, in their voice, at most 40
                characters, that you or the program can answer (a question about their data, "Propose la
                séance de jeudi", "Compare à ma sortie du 4 octobre", "Comment exécuter ma séance de demain ?").
                Context computed by the program:
                %s
                Recent conversation, oldest first (the athlete's own words and your replies):
                %s
                The athlete's message:
                %s""".formatted(RECENT_DAYS, String.join("\n", this.history.metrics()),
                PromptFence.data("race-radio.context", JSON.writeValueAsString(context)),
                PromptFence.data("race-radio.conversation", history.toString()), athleteMessage);
        return structured ? structuredForm(text) : text;
    }

    /** Facts kept as JSON text, read back so that references can reach inside them. */
    private static Object parsed(String json) {
        try {
            return JSON.readTree(json);
        } catch (RuntimeException e) {
            return json;
        }
    }

    static final String STRUCTURED_MARKER = "Answer with JSON only, in this shape";

    /** The same task, answered as JSON: the delegation in its own field, every figure a reference. */
    static String structuredForm(String prose) {
        String out = prose
                .replace("then reply with exactly one line and\nnothing else, and the program will answer",
                        "then put exactly that one line in \"delegate\", and the program will answer")
                .replace("""
                Otherwise, after your answer, add a line SUGGESTIONS: and three lines starting with "- ", each
                a short message the athlete is likely to send next, in French, in their voice, at most 40
                characters, that you or the program can answer (a question about their data, "Propose la
                séance de jeudi", "Compare à ma sortie du 4 octobre", "Comment exécuter ma séance de demain ?").
                """, STRUCTURED_MARKER + """
                , nothing before or after it:
                {"reply": "your answer, or empty when you delegate",
                 "suggestions": ["three short messages the athlete is likely to send next, in French, in their
                                 voice, at most 40 characters each, that you or the program can answer"],
                 "delegate": "the one program line (JOUR, ACTIVITE, COMPARE, TENDANCE, SEANCE, SEMAINE, DEPLACER, MODIFIER), or empty"}
                In "reply", a figure held alone in a field of the context is a reference in braces, never typed:
                {latestSessionReview.facts.power.load}, {readinessToday.score}; the program puts the value in,
                so write the unit only when the value lacks it. A figure that sits inside a longer text of the
                context may be copied exactly as written there. Never compute or estimate a figure. A reference
                to a field that does not exist refuses the answer. Small counts and days (up to 31) may be typed.
                """);
        if (!out.contains(STRUCTURED_MARKER) || out.contains("SUGGESTIONS:")) {
            throw new IllegalStateException("the conversation prompt changed: its structured form no longer applies");
        }
        return out;
    }
}
