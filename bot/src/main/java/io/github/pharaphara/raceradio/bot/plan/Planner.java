package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.core.plan.Feasibility;
import io.github.pharaphara.raceradio.core.plan.Objective;
import io.github.pharaphara.raceradio.core.plan.Phase;
import io.github.pharaphara.raceradio.core.plan.PlannedSession;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.plan.WeekBuilder;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import io.github.pharaphara.raceradio.icu.model.Athlete;
import io.github.pharaphara.raceradio.icu.model.SportSettings;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The planner as the athlete meets it: next week, feasibility, publication, Sunday recap. */
public final class Planner {

    public record Week(LocalDate monday, Phase phase, List<PlannedSession> sessions) {
    }

    private final IntervalsClient intervals;
    private final IntervalsWriter writer;
    private final Plans plans;
    private final Integer swimCssOverride;
    private final double rampPerWeek;
    private final Clock clock;

    public Planner(IntervalsClient intervals, IntervalsWriter writer, Plans plans, Integer swimCssOverride, double rampPerWeek, Clock clock) {
        this.intervals = intervals;
        this.writer = writer;
        this.plans = plans;
        this.swimCssOverride = swimCssOverride;
        this.rampPerWeek = rampPerWeek;
        this.clock = clock;
    }

    Thresholds thresholds() {
        Athlete a = intervals.athlete();
        Integer ftp = a.settingsFor("Ride").map(SportSettings::thresholdPower).orElse(null);
        Integer run = a.settingsFor("Run").map(SportSettings::thresholdSpeed).map(v -> (int) Math.round(1000 / v)).orElse(null);
        Integer css = swimCssOverride != null ? swimCssOverride
                : a.settingsFor("Swim").map(SportSettings::thresholdSpeed).map(v -> (int) Math.round(100 / v)).orElse(null);
        return new Thresholds(ftp, run, css);
    }

    /** Whether published workouts really reach the calendar, or are only described (dry run). */
    public boolean publishes() {
        return writer.allows(IntervalsWriter.Write.WORKOUTS);
    }

    public Week nextWeek() {
        LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return week(monday);
    }

    Week week(LocalDate monday) {
        List<Objective> objectives = plans.objectives().stream().map(Plans.StoredObjective::objective).toList();
        Phase phase = Phase.of(monday, objectives, plans.seasonStart().orElse(monday));
        return new Week(monday, phase, WeekBuilder.build(monday, phase, plans.availability(), plans.sports(), thresholds()));
    }

    /** Against the nearest A race, when there is one. */
    public Optional<Feasibility> feasibility() {
        LocalDate today = LocalDate.now(clock);
        Optional<Objective> race = plans.objectives().stream().map(Plans.StoredObjective::objective)
                .filter(o -> o.kind() == Objective.Kind.RACE && o.priority() == Objective.Priority.A && o.date().isAfter(today))
                .min(Comparator.comparing(Objective::date));
        if (race.isEmpty()) {
            return Optional.empty();
        }
        double fitness = intervals.wellness(today.minusDays(3), today).stream()
                .filter(w -> w.fitness() != null).max(Comparator.comparing(WellnessDay::date))
                .map(WellnessDay::fitness).orElse(0.0);
        double hours = plans.availability().values().stream().mapToInt(Integer::intValue).sum() / 60.0;
        int weeks = (int) (ChronoUnit.DAYS.between(today, race.get().date()) / 7);
        return Optional.of(Feasibility.of(fitness, hours, weeks, rampPerWeek));
    }

    /** Publishes every workout of the week. Returns one line per problem, empty when all went in. */
    public List<String> publish(Week w) {
        List<String> problems = new ArrayList<>();
        for (PlannedSession s : w.sessions()) {
            try {
                writer.publishWorkout(s.day(), s.workout());
            } catch (RuntimeException e) {
                problems.add(day(s.day()) + " " + s.workout().name() + " : " + e.getMessage());
            }
        }
        return problems;
    }

    public String describe(Week w) {
        StringBuilder b = new StringBuilder("Semaine du " + w.monday().getDayOfMonth() + " " + month(w.monday()) + " · phase " + phase(w.phase()));
        if (w.sessions().isEmpty()) {
            b.append("\nAucune séance : indique tes disponibilités avec /dispo.");
        }
        for (PlannedSession s : w.sessions()) {
            b.append("\n• ").append(day(s.day())).append(" · ").append(s.workout().name());
        }
        int total = w.sessions().stream().mapToInt(PlannedSession::minutes).sum();
        if (total > 0) {
            b.append("\nTotal environ ").append(total / 60).append(" h ").append(String.format("%02d", total % 60));
        }
        return b.toString();
    }

    public String describe(Feasibility f) {
        String limit = "time".equals(f.limitedBy())
                ? "ce sont tes heures disponibles qui plafonnent la progression"
                : "c'est la montée de charge raisonnable (" + Math.round(rampPerWeek) + " points par semaine) qui fixe le rythme";
        return String.format(Locale.FRANCE, "Faisabilité : forme de fond actuelle %d ; tes heures la porteraient vers %d à long terme ;"
                        + " d'ici la course (%d semaines) environ %d. %s. Un chrono ne se déduit pas de cette courbe.",
                Math.round(f.current()), Math.round(f.plateau()), f.weeks(), Math.round(f.byDate()), capitalize(limit));
    }

    /** Last seven days, by sport. */
    public String recap() {
        LocalDate today = LocalDate.now(clock);
        Map<String, int[]> bySport = new LinkedHashMap<>();
        for (ActivitySummary a : intervals.activities(today.minusDays(6), today)) {
            String sport = family(a.type());
            int[] v = bySport.computeIfAbsent(sport, k -> new int[2]);
            v[0] += a.movingSeconds() == null ? 0 : a.movingSeconds();
            v[1] += a.load() == null ? 0 : a.load();
        }
        StringBuilder b = new StringBuilder("Ta semaine :");
        if (bySport.isEmpty()) {
            b.append(" aucune séance enregistrée.");
        }
        bySport.forEach((sport, v) -> b.append("\n• ").append(sport).append(" ").append(v[0] / 3600).append(" h ")
                .append(String.format("%02d", (v[0] % 3600) / 60)).append(", charge ").append(v[1]));
        return b.toString();
    }

    static String family(String type) {
        if (type == null) {
            return "autre";
        }
        if (type.contains("Ride")) {
            return "vélo";
        }
        if (type.contains("Run")) {
            return "course";
        }
        if (type.contains("Swim")) {
            return "natation";
        }
        return "renforcement et autres";
    }

    private static String phase(Phase p) {
        return switch (p) {
            case BASE -> "de base";
            case SPECIFIC -> "spécifique";
            case TAPER -> "d'affûtage";
            case RACE_WEEK -> "de course";
            case RECOVERY_WEEK -> "allégée";
            case MAINTENANCE -> "d'entretien";
        };
    }

    private static String day(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE);
    }

    private static String month(LocalDate d) {
        return d.getMonth().getDisplayName(TextStyle.FULL, Locale.FRANCE);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
