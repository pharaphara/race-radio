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

    public String answer(String athleteMessage) {
        String prompt = prompt(athleteMessage);
        memory.add(clock.instant(), "athlete", athleteMessage);
        String reply;
        try {
            ModelReply r = model.ask(Purpose.CHAT, "low", 1_500, prompt);
            reply = r.refused() || r.text().isBlank()
                    ? "Je ne peux pas répondre à ça. Les commandes restent disponibles : /aide."
                    : r.text();
            Optional<String> looked = lookup(reply.strip());
            if (looked.isPresent()) {
                memory.add(clock.instant(), "coach", "[" + reply.strip() + "] " + looked.get());
                return looked.get().isEmpty() ? null : looked.get();
            }
        } catch (CoachModel.BudgetExhausted e) {
            reply = "Le budget du modèle est atteint ce mois-ci : je ne peux plus converser, mais /forme, /aujourdhui et /pourquoi fonctionnent.";
        } catch (RuntimeException e) {
            LOG.warn("conversation failed: {}", e.getMessage());
            reply = "Je n'arrive pas à joindre le modèle pour l'instant. Réessaie dans un moment.";
        }
        memory.add(clock.instant(), "coach", reply);
        return reply;
    }

    private static final java.util.regex.Pattern DAY_LOOKUP = java.util.regex.Pattern.compile("^JOUR (\\d{4}-\\d{2}-\\d{2})$");
    private static final java.util.regex.Pattern TREND_LOOKUP = java.util.regex.Pattern.compile("^TENDANCE (\\w+) (\\d{1,2})$");

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
        context.put("lastMornings", history.recentDays(RECENT_DAYS));
        context.put("lastSessions", history.recentSessions(RECENT_DAYS));
        decisions.latestOf("REVIEW").ifPresent(d -> context.put("latestSessionReview", Map.of("day", d.day(), "message", d.message())));
        List<Conversations.Note> notes = memory.activeNotes();
        context.put("athleteNotes", notes.stream().map(n -> n.at().substring(0, 10) + " " + n.text()).toList());
        List<Conversations.Turn> turns = memory.last(TURNS);
        StringBuilder history = new StringBuilder();
        for (Conversations.Turn t : turns) {
            history.append(t.at(), 0, 16).append(' ').append(t.role()).append(": ").append(t.text()).append('\n');
        }
        return """
                Task: answer the athlete's message below, in French, for Telegram.
                Be brief: one to three sentences unless they ask for more. Use only the data given.
                You cannot change the plan, a workout or a verdict from here. If they ask for that, say which
                command does it: /ressenti to report how they feel, /note to keep a fact, /aujourdhui to redo
                the morning call, /seance followed by the day and the workout to create one workout
                (for example: /seance demain vélo 1h endurance). If they report pain that persists, or a warning sign, recommend a health
                professional, once and plainly.
                The context holds the last %d mornings and sessions. When the question needs more than that,
                do not answer it yourself and do not guess: reply with exactly one line, and the program will
                answer with the real figures from its archive:
                JOUR YYYY-MM-DD  for one day older than the context (wellness and sessions of that day)
                TENDANCE <key> <months>  for a figure over time, months from 2 to 36, key among:
                %s
                Context computed by the program:
                %s
                Recent conversation, oldest first (the athlete's own words and your replies):
                %s
                The athlete's message:
                %s""".formatted(RECENT_DAYS, String.join("\n", this.history.metrics()),
                PromptFence.data("race-radio.context", JSON.writeValueAsString(context)),
                PromptFence.data("race-radio.conversation", history.toString()), athleteMessage);
    }
}
