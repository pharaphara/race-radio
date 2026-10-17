package io.github.pharaphara.raceradio.bot.morning;

import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.PromptFence;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.FeelStore;
import io.github.pharaphara.raceradio.core.readiness.DayRecord;
import io.github.pharaphara.raceradio.core.readiness.Readiness;
import io.github.pharaphara.raceradio.core.readiness.ReadinessInput;
import io.github.pharaphara.raceradio.core.readiness.ReadinessModel;
import io.github.pharaphara.raceradio.core.readiness.Temperament;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The morning call: read Intervals.icu, decide in Java, let the model put it in words, and leave
 * a trace in three places: the athlete's Telegram, a note on the Intervals.icu calendar, and the
 * decision log. Without the model, the deterministic wording is sent instead and the day goes on.
 */
public final class MorningCheck {

    private static final Logger LOG = LoggerFactory.getLogger(MorningCheck.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Baseline window plus a margin for missing mornings. */
    private static final int HISTORY_DAYS = ReadinessModel.BASELINE_MAX_DAYS + 15;

    public record Outcome(Readiness readiness, String message, boolean worded) {
    }

    private final IntervalsClient intervals;
    private final IntervalsWriter writer;
    private final CoachModel model;
    private final FeelStore feels;
    private final DecisionLog decisions;
    private final Temperament temperament;
    private final String effort;
    private final Clock clock;

    public MorningCheck(IntervalsClient intervals, IntervalsWriter writer, CoachModel model, FeelStore feels,
                        DecisionLog decisions, Temperament temperament, String effort, Clock clock) {
        this.intervals = intervals;
        this.writer = writer;
        this.model = model;
        this.feels = feels;
        this.decisions = decisions;
        this.temperament = temperament;
        this.effort = effort;
        this.clock = clock;
    }

    /** The verdict alone, computed and not worded: cheap, used by /forme. */
    public Readiness assess() {
        LocalDate today = LocalDate.now(clock);
        List<WellnessDay> wellness = intervals.wellness(today.minusDays(HISTORY_DAYS), today);
        List<ActivitySummary> recent = intervals.activities(today.minusDays(3), today.minusDays(1));
        return ReadinessModel.assess(input(today, wellness, recent), temperament);
    }

    ReadinessInput input(LocalDate today, List<WellnessDay> wellness, List<ActivitySummary> recent) {
        List<DayRecord> history = new ArrayList<>(wellness.stream()
                .filter(w -> w.date() != null && !w.date().isAfter(today))
                .sorted(Comparator.comparing(WellnessDay::date))
                .map(w -> new DayRecord(w.date(), w.hrv(), w.restingHeartRate(), w.sleepSeconds(), w.sleepScore()))
                .toList());
        if (history.isEmpty() || !history.getLast().date().equals(today)) {
            // No record this morning yet: judged as missing, never filled in.
            history.add(new DayRecord(today, null, null, null, null));
        }
        Optional<WellnessDay> now = wellness.stream().filter(w -> today.equals(w.date())).findFirst();
        double load3 = recent.stream()
                .filter(a -> a.start() != null && a.start().toLocalDate().isBefore(today))
                .mapToDouble(a -> a.load() == null ? 0 : a.load()).sum();
        return new ReadinessInput(history, now.map(WellnessDay::fitness).orElse(null), now.map(WellnessDay::fatigue).orElse(null),
                load3, feels.on(today).orElse(null), feels.before(today, 2));
    }

    /**
     * Runs the full call. {@code publish} leaves the note on the calendar and records the
     * decision; the scheduled run does, an on-demand /aujourdhui does not repeat it.
     */
    public Outcome run(boolean publish) {
        LocalDate today = LocalDate.now(clock);
        Readiness r = assess();
        String fallback = Wording.deterministic(r);
        String message = fallback;
        boolean worded = false;
        try {
            ModelReply reply = model.ask(Purpose.MORNING, effort, 2_000, prompt(r));
            if (!reply.refused() && !reply.text().isBlank()) {
                message = ensureVerdictFirst(reply.text(), r);
                worded = true;
            }
        } catch (CoachModel.BudgetExhausted e) {
            message = fallback + "\n\n(Budget du modèle atteint ce mois-ci : message non rédigé.)";
        } catch (RuntimeException e) {
            LOG.warn("the model could not word the morning message: {}", e.getMessage());
        }
        if (publish && !decisions.exists("MORNING", today)) {
            writer.publishNote(today, Wording.verdictLine(r.verdict()) + " · race-radio", message
                    + "\n\n" + r.model() + ", consignes " + model.promptVersion());
            decisions.record(clock.instant(), "MORNING", today, r.verdict().name(), r.model(),
                    JSON.writeValueAsString(r), message, model.promptVersion());
        }
        return new Outcome(r, message, worded);
    }

    String prompt(Readiness r) {
        return """
                Task: write this morning's message to the athlete, in French, for Telegram.
                The verdict is decided; do not change it. Start with exactly this line:
                %s
                Then two or three short sentences. Name the signal behind each statement, use only the
                numbers given, and never invent one. Say what the day means in practice. If a single
                reading is off and the verdict is green, mention it in passing without alarm.
                %s""".formatted(Wording.verdictLine(r.verdict()), PromptFence.data("race-radio.readiness", JSON.writeValueAsString(r)));
    }

    /**
     * The verdict line is the one thing the model may not change. If its first line is a verdict
     * line, it is replaced by the right one; otherwise the right one is put first.
     */
    static String ensureVerdictFirst(String text, Readiness r) {
        String line = Wording.verdictLine(r.verdict());
        String first = text.lines().findFirst().orElse("");
        if (first.startsWith("🟢") || first.startsWith("🟡") || first.startsWith("🔴")) {
            String rest = text.substring(first.length()).stripLeading();
            return rest.isEmpty() ? line : line + "\n" + rest;
        }
        return line + "\n" + text;
    }
}
