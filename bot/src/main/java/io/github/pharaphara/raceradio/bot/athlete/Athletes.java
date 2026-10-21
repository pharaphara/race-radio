package io.github.pharaphara.raceradio.bot.athlete;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The {@code athlete} table: who the coach serves, and how. Shared by design: it holds no one's training data. */
public final class Athletes {

    private static final String COLUMNS = "id, chat_id, name, role, intervals_athlete_id, intervals_key, model_route, "
            + "model_fallback, monthly_budget_usd, writes, status";
    private static final RowMapper<Athlete> ROW = (rs, i) -> new Athlete(new AthleteId(rs.getLong("id")),
            rs.getObject("chat_id") == null ? null : rs.getLong("chat_id"), rs.getString("name"),
            Athlete.Role.valueOf(rs.getString("role").toUpperCase(Locale.ROOT)), rs.getString("intervals_athlete_id"),
            rs.getString("intervals_key"), rs.getString("model_route"), rs.getInt("model_fallback") == 1,
            rs.getObject("monthly_budget_usd") == null ? null : BigDecimal.valueOf(rs.getDouble("monthly_budget_usd")),
            rs.getString("writes"), "active".equals(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public Athletes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Athlete> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM athlete ORDER BY id", ROW);
    }

    public List<Athlete> active() {
        return all().stream().filter(Athlete::active).toList();
    }

    public Optional<Athlete> byId(AthleteId id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM athlete WHERE id = ?", ROW, id.value()).stream().findFirst();
    }

    /** The active athlete this chat belongs to, if any. */
    public Optional<Athlete> byChat(long chatId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM athlete WHERE chat_id = ? AND status = 'active'", ROW, chatId).stream().findFirst();
    }

    public Athlete administrator() {
        return jdbc.query("SELECT " + COLUMNS + " FROM athlete WHERE role = 'admin' ORDER BY id LIMIT 1", ROW).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no administrator: the database was not prepared"));
    }
}
