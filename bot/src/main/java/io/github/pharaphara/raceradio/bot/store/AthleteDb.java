package io.github.pharaphara.raceradio.bot.store;

import io.github.pharaphara.raceradio.bot.athlete.AthleteId;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database as one athlete sees it: every class that keeps an athlete's data is built with one,
 * so none can be built without saying whose data it holds. Each query still names the athlete
 * itself ({@code WHERE athlete_id = ?}, {@code id()} as the value); {@code AthleteScopeTest} checks
 * that none forgets.
 */
public record AthleteDb(JdbcTemplate jdbc, AthleteId athlete) {

    /** The athlete's id, as a query parameter. */
    public long id() {
        return athlete.value();
    }
}
