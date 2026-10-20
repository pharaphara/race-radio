package io.github.pharaphara.raceradio.bot.store;

import io.github.pharaphara.raceradio.core.plan.Objective;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Objectives and the settings the planner reads. */
public class Plans {

    /**
     * How much the coach does with the calendar. Nothing is ever planned unasked: a workout or a
     * week is created on request (/seance, /semaine) and published only once the athlete agrees.
     */
    public enum Mode {
        /** Creates on request, and lightens its own workouts on yellow and red mornings. */
        ON_DEMAND,
        /** Never touches the calendar: reads what another tool planned and comments on it. */
        ANALYSE;

        /** Reads a stored value, including those of the former modes (PLAN, ADAPT, OFF). */
        static Mode stored(String v) {
            return switch (v) {
                case "OFF", "ANALYSE" -> ANALYSE;
                default -> ON_DEMAND;
            };
        }
    }

    public record StoredObjective(long id, Objective objective) {
    }

    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;

    public Plans(AthleteDb db) {
        this.jdbc = db.jdbc();
        this.athlete = db.id();
    }

    public long addObjective(Objective o, Instant at) {
        Long id = jdbc.queryForObject("INSERT INTO objective (athlete_id, kind, sport, day, priority, target, at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                athlete, o.kind().name(), o.sport(), o.date() == null ? null : o.date().toString(), o.priority().name(), o.target(), at.toString());
        if (get("season_start").isEmpty()) {
            set("season_start", LocalDate.ofInstant(at, java.time.ZoneOffset.UTC).with(DayOfWeek.MONDAY).toString());
        }
        return id == null ? 0 : id;
    }

    public List<StoredObjective> objectives() {
        return jdbc.query("SELECT id, kind, sport, day, priority, target FROM objective WHERE athlete_id = ? AND active = 1 ORDER BY id",
                (rs, i) -> new StoredObjective(rs.getLong(1), new Objective(Objective.Kind.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4) == null ? null : LocalDate.parse(rs.getString(4)), Objective.Priority.valueOf(rs.getString(5)),
                        rs.getString(6))), athlete);
    }

    public boolean withdrawObjective(long id) {
        return jdbc.update("UPDATE objective SET active = 0 WHERE athlete_id = ? AND id = ? AND active = 1", athlete, id) == 1;
    }

    public Mode mode() {
        return get("mode").map(Mode::stored).orElse(Mode.ON_DEMAND);
    }

    public void mode(Mode m) {
        set("mode", m.name());
    }

    /** Sports in season; all three by default. */
    public Set<String> sports() {
        return get("sports").map(v -> (Set<String>) new LinkedHashSet<>(List.of(v.split(",")))).orElse(new LinkedHashSet<>(List.of("Swim", "Ride", "Run")));
    }

    public void sports(Set<String> s) {
        set("sports", String.join(",", s));
    }

    /** Minutes available per weekday; empty until the athlete sets it. */
    public Map<DayOfWeek, Integer> availability() {
        Map<DayOfWeek, Integer> m = new EnumMap<>(DayOfWeek.class);
        get("availability").ifPresent(v -> {
            for (String part : v.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                String[] kv = part.split("=");
                m.put(DayOfWeek.valueOf(kv[0]), Integer.parseInt(kv[1]));
            }
        });
        return m;
    }

    public void availability(Map<DayOfWeek, Integer> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((d, v) -> b.append(b.isEmpty() ? "" : ",").append(d).append('=').append(v));
        set("availability", b.toString());
    }

    public Optional<LocalDate> seasonStart() {
        return get("season_start").map(LocalDate::parse);
    }

    private Optional<String> get(String key) {
        return jdbc.query("SELECT value FROM setting WHERE athlete_id = ? AND key = ?", (rs, i) -> rs.getString(1), athlete, key)
                .stream().findFirst();
    }

    private void set(String key, String value) {
        jdbc.update("INSERT INTO setting (athlete_id, key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT(athlete_id, key) DO UPDATE SET value = excluded.value", athlete, key, value);
    }
}
