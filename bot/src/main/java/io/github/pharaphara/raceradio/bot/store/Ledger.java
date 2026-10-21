package io.github.pharaphara.raceradio.bot.store;

import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.ModelRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/** The record of every call to the model and its cost. */
public class Ledger {

    private final JdbcTemplate jdbc;
    /** Whose data this is: every query below names it. */
    private final long athlete;

    public Ledger(AthleteDb db) {
        this.jdbc = db.jdbc();
        this.athlete = db.id();
    }

    public void record(Instant at, ModelRequest req, ModelReply reply, BigDecimal cost, String journalRef, String billing) {
        jdbc.update("""
                INSERT INTO llm_call (athlete_id, at, purpose, requested_model, answering_model, input_tokens, output_tokens,
                    cache_write_tokens, cache_read_tokens, cost_usd, latency_ms, stop_reason, prompt_version, journal_ref, billing)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                athlete, at.toString(), req.purpose().name(), req.model(), reply.answeringModel(),
                reply.usage().input(), reply.usage().output(), reply.usage().cacheWrite(), reply.usage().cacheRead(),
                cost.doubleValue(), reply.latencyMs(), reply.stopReason(), req.promptVersion(), journalRef, billing);
    }

    public BigDecimal costOf(YearMonth month, ZoneId zone) {
        Double sum = jdbc.queryForObject("SELECT COALESCE(SUM(cost_usd), 0) FROM llm_call WHERE athlete_id = ? AND at >= ? AND at < ?",
                Double.class, athlete, start(month, zone), start(month.plusMonths(1), zone));
        return BigDecimal.valueOf(sum == null ? 0 : sum);
    }

    /** One line per purpose for the month: calls, tokens by kind, cost. */
    public List<PurposeTotal> totals(YearMonth month, ZoneId zone) {
        return jdbc.query("""
                SELECT purpose, COUNT(*) AS calls, SUM(input_tokens) AS input, SUM(output_tokens) AS output,
                       SUM(cache_write_tokens) AS cw, SUM(cache_read_tokens) AS cr, SUM(cost_usd) AS cost
                FROM llm_call WHERE athlete_id = ? AND at >= ? AND at < ? GROUP BY purpose ORDER BY cost DESC""",
                (rs, i) -> new PurposeTotal(rs.getString("purpose"), rs.getInt("calls"), rs.getLong("input"),
                        rs.getLong("output"), rs.getLong("cw"), rs.getLong("cr"), BigDecimal.valueOf(rs.getDouble("cost"))),
                athlete, start(month, zone), start(month.plusMonths(1), zone));
    }

    public record PurposeTotal(String purpose, int calls, long input, long output, long cacheWrite, long cacheRead, BigDecimal cost) {
    }

    private static String start(YearMonth m, ZoneId zone) {
        return m.atDay(1).atStartOfDay(zone).toInstant().toString();
    }
}
