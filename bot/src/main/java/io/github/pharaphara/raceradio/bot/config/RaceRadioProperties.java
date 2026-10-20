package io.github.pharaphara.raceradio.bot.config;

import io.github.pharaphara.raceradio.core.readiness.Temperament;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Everything that can be configured, in one place. Secrets come from the environment (the
 * {@code .env} file next to the service), never from this repository.
 */
@ConfigurationProperties(prefix = "race-radio")
public record RaceRadioProperties(
        boolean dryRun,
        ZoneId zone,
        Path dataDir,
        Temperament temperament,
        Telegram telegram,
        Intervals intervals,
        Model model,
        Schedule schedule,
        Athlete athlete) {

    /**
     * Settings about the athlete that the calendar does not hold, or holds wrong.
     *
     * @param swimCssSeconds  critical swim speed in seconds per 100 m from a 400/200 test; overrides
     *                        the profile's swim threshold, which is often a guess. Null to use it.
     * @param rampPerWeek     how much fitness may rise per week; 5 to 7 suits an intermediate athlete
     */
    public record Athlete(Integer swimCssSeconds, double rampPerWeek) {
    }

    public record Telegram(String token, long allowedChatId) {
    }

    public record Intervals(String athleteId, String apiKey) {
    }

    /**
     * @param transport       {@code subscription}: through the Claude Code command line and a Claude
     *                        subscription; {@code api}: through the Anthropic API, billed per token;
     *                        {@code openai}: any service speaking the OpenAI chat format with a key,
     *                        OpenRouter by default
     * @param baseUrl         {@code openai} transport: the service's address, empty for OpenRouter
     * @param endpointKey     {@code openai} transport: its API key, empty for a local model
     * @param fallbackTransport  a second transport asked when the first fails or declines, or empty
     * @param fallbackName    the model the second transport asks for
     * @param guard           answers from an {@code openai} transport are checked for invented numbers
     *                        and baseless explanations: {@code enforce} refuses them (the fallback then
     *                        answers), {@code report} only logs them, {@code off} skips the check
     * @param claudeCommand   path of the {@code claude} command, for the subscription transport
     * @param prices          dollars per million tokens, per answering model, for the API transport;
     *                        prices change, so they live here and not in code
     * @param monthlyBudget   dollars at list price; past it the coach keeps computing and publishing
     *                        but stops writing prose, and says so. On a subscription nothing is billed,
     *                        but the coach draws on the same usage limits as the athlete's own use of
     *                        Claude, and this cap protects them
     * @param fallbacks       API transport: let the API answer from another model when this one declines
     * @param journalDays     how long the journal of what was sent to the model is kept
     */
    public record Model(String transport, String claudeCommand, String apiKey, String name,
                        String morningEffort, String reviewEffort, boolean fallbacks,
                        BigDecimal monthlyBudget, Map<String, Prices> prices, int journalDays,
                        String baseUrl, String endpointKey, String fallbackTransport, String fallbackName, String guard) {
    }

    public record Prices(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {
    }

    /** The morning call, and the daytime window in which new activities are looked for. */
    public record Schedule(LocalTime morningAt, LocalTime watchFrom, LocalTime watchUntil, int watchEveryMinutes) {
    }
}
