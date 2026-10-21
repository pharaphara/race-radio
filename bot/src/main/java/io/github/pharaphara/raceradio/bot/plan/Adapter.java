package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.plan.Workouts;
import io.github.pharaphara.raceradio.core.readiness.Verdict;
import io.github.pharaphara.raceradio.core.workout.Workout;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lightens the day on a yellow or red morning, and undoes it on request.
 *
 * <p>Only the coach's own workouts are touched; anything planned by hand or by another tool is
 * left alone and merely mentioned. Yellow: a hard workout becomes endurance at about seventy
 * percent of its length, the easy ones stay. Red: each workout becomes thirty minutes of recovery,
 * swimming is dropped. The originals are kept so that the change can be undone with one button.
 */
public final class Adapter {

    public record Result(long batch, String summary) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> HARD_WORDS = List.of("seuil", "sweet", "enchaînement", "vo2", "tempo", "intervalle");

    private final IntervalsClient intervals;
    private final IntervalsWriter writer;
    private final Plans plans;
    private final Planner planner;
    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;
    private final Clock clock;

    public Adapter(IntervalsClient intervals, IntervalsWriter writer, Plans plans, Planner planner, AthleteDb db, Clock clock) {
        this.intervals = intervals;
        this.writer = writer;
        this.plans = plans;
        this.planner = planner;
        this.jdbc = db.jdbc();
        this.athlete = db.id();
        this.clock = clock;
    }

    public Optional<Result> adapt(Verdict verdict) {
        if (verdict == Verdict.GREEN || plans.mode() == Plans.Mode.ANALYSE) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock);
        List<CalendarEvent> own = intervals.events(today, today).stream()
                .filter(e -> "WORKOUT".equals(e.category()) && e.ownedByRaceRadio()).toList();
        if (own.isEmpty()) {
            return Optional.empty();
        }
        Thresholds t = planner.thresholds();
        long batch = clock.millis();
        List<String> lines = new ArrayList<>();
        for (CalendarEvent e : own) {
            boolean hard = HARD_WORDS.stream().anyMatch(w -> e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(w));
            if (verdict == Verdict.YELLOW && !hard) {
                continue;
            }
            Optional<Workout> replacement = replacement(verdict, e, t);
            // Replacement first, original second: if the calendar misreads the replacement, the
            // publication fails and the original is still there.
            Long replacementId = null;
            if (replacement.isPresent()) {
                try {
                    replacementId = writer.publishWorkout(today, replacement.get()).map(CalendarEvent::id).orElse(null);
                } catch (RuntimeException ex) {
                    lines.add("« " + e.name() + " » laissée telle quelle (le remplacement n'a pas pu être publié)");
                    continue;
                }
            }
            writer.deleteOwnEvent(e.id());
            jdbc.update("INSERT INTO adaptation (athlete_id, at, day, original_json, replacement_id) VALUES (?, ?, ?, ?, ?)",
                    athlete, String.valueOf(batch), today.toString(), JSON.writeValueAsString(e), replacementId);
            lines.add("« " + e.name() + " » " + replacement.map(w -> "remplacée par « " + w.name() + " »").orElse("retirée"));
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Result(batch, "J'ai ajusté ta journée : " + String.join(" ; ", lines) + "."));
    }

    /** Restores the originals of one adjustment. */
    public String undo(long batch) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, original_json, replacement_id FROM adaptation WHERE athlete_id = ? AND at = ? AND undone = 0",
                athlete, String.valueOf(batch));
        if (rows.isEmpty()) {
            return "Rien à annuler.";
        }
        List<String> restored = new ArrayList<>();
        for (var r : rows) {
            CalendarEvent original = JSON.readValue((String) r.get("original_json"), CalendarEvent.class);
            if (r.get("replacement_id") != null) {
                writer.deleteOwnEvent(((Number) r.get("replacement_id")).longValue());
            }
            writer.publishText(original.start().toLocalDate(), original.type(), original.name(), original.description());
            jdbc.update("UPDATE adaptation SET undone = 1 WHERE athlete_id = ? AND id = ?", athlete, r.get("id"));
            restored.add("« " + original.name() + " »");
        }
        return "Annulé : " + String.join(", ", restored) + " remis au calendrier.";
    }

    private static Optional<Workout> replacement(Verdict v, CalendarEvent e, Thresholds t) {
        String sport = e.type() == null ? "" : e.type();
        int minutes = e.movingSeconds() == null ? 45 : Math.max(20, e.movingSeconds() / 60);
        boolean bike = sport.contains("Ride") && t.thresholdPower() != null;
        boolean run = sport.contains("Run") && t.thresholdPace() != null;
        if (v == Verdict.RED) {
            if (bike) {
                return Optional.of(Workouts.recovery("Ride", t, 30));
            }
            return run ? Optional.of(Workouts.recovery("Run", t, 30)) : Optional.empty();
        }
        int shorter = Math.max(20, (int) Math.round(minutes * 0.7 / 5) * 5);
        if (bike) {
            return Optional.of(Workouts.bikeEndurance(t.thresholdPower(), shorter));
        }
        return run ? Optional.of(Workouts.runEasy(t.thresholdPace(), shorter, false)) : Optional.empty();
    }
}
