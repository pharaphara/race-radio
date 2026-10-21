package io.github.pharaphara.raceradio.bot.athlete;

import java.math.BigDecimal;

/**
 * One athlete as the {@code athlete} table holds them.
 *
 * @param chatId          the Telegram chat the coach talks to them in; null until they checked in
 * @param intervalsKey    encrypted; null for the administrator, whose key is in the configuration
 * @param writes          the kinds of writes sent to Intervals.icu, as {@code RACE_RADIO_WRITES} spells
 *                        them; the administrator's come from the configuration
 * @param monthlyBudget   dollars at list price; null for the configured default
 */
public record Athlete(AthleteId id, Long chatId, String name, Role role, String intervalsAthleteId, String intervalsKey,
                      String modelRoute, boolean modelFallback, BigDecimal monthlyBudget, String writes, boolean active) {

    public enum Role {
        ADMIN,
        ATHLETE,
        COACH
    }

    public boolean administrator() {
        return role == Role.ADMIN;
    }

    /** How the coach names them in logs: never their chat or their keys. */
    public String label() {
        return (name == null || name.isBlank() ? "athlete" : name) + " #" + id.value();
    }
}
