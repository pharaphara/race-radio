package io.github.pharaphara.raceradio.bot.config;

import io.github.pharaphara.raceradio.bot.commands.CommandRouter;
import io.github.pharaphara.raceradio.bot.llm.AnthropicTransport;
import io.github.pharaphara.raceradio.bot.llm.ClaudeCodeTransport;
import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.Journal;
import io.github.pharaphara.raceradio.bot.llm.ModelTransport;
import io.github.pharaphara.raceradio.bot.llm.Pricing;
import io.github.pharaphara.raceradio.bot.llm.SystemPrompt;
import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.review.ActivityWatcher;
import io.github.pharaphara.raceradio.bot.review.SessionReview;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.FeelStore;
import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.bot.store.ReviewedActivities;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.bot.telegram.TelegramPoller;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Assembles the service from the configuration. */
@Configuration
public class BotConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(BotConfiguration.class);

    /** Standing instructions, then the knowledge base, in this order. */
    static final List<String> PROMPT = List.of("prompt/voice.md", "knowledge/README.md", "knowledge/readiness.md",
            "knowledge/load.md", "knowledge/zones.md", "knowledge/bike.md", "knowledge/run.md", "knowledge/swim.md",
            "knowledge/strength.md", "knowledge/periodization.md", "knowledge/sessions.md", "knowledge/nutrition.md");

    private final RaceRadioProperties props;

    public BotConfiguration(RaceRadioProperties props) {
        this.props = props;
        try {
            Files.createDirectories(props.dataDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    Clock clock() {
        return Clock.system(props.zone());
    }

    /** The database file brought to the current schema before the pool opens it. */
    @Bean
    io.github.pharaphara.raceradio.bot.store.Database.Outcome database(
            @org.springframework.beans.factory.annotation.Value("${race-radio.once:}") String once) {
        var outcome = io.github.pharaphara.raceradio.bot.store.Database.prepare(props.dataDir().resolve("race-radio.db"),
                new io.github.pharaphara.raceradio.bot.store.Database.Administrator(props.telegram().allowedChatId(),
                        props.intervals().athleteId()), "reset".equals(once));
        LOG.info("database: {}", outcome.action());
        return outcome;
    }

    /** Declared here so that nothing reaches the database before it is prepared. */
    @Bean
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource,
                                                            io.github.pharaphara.raceradio.bot.store.Database.Outcome database) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    /** The administrator's view of the database; the only athlete until athletes can be invited. */
    @Bean
    io.github.pharaphara.raceradio.bot.store.AthleteDb administratorDb(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                                       io.github.pharaphara.raceradio.bot.store.Database.Outcome database) {
        return new io.github.pharaphara.raceradio.bot.store.AthleteDb(jdbc,
                new io.github.pharaphara.raceradio.bot.athlete.AthleteId(database.administratorId()));
    }

    @Bean
    ModelTransport modelTransport() {
        ModelTransport primary = transport(props.model().transport());
        String fallback = props.model().fallbackTransport();
        if (fallback == null || fallback.isBlank()) {
            LOG.info("model: {} through {}", props.model().name(), props.model().transport());
            return primary;
        }
        LOG.info("model: {} through {}, then {} through {}", props.model().name(), props.model().transport(),
                props.model().fallbackName(), fallback);
        return new io.github.pharaphara.raceradio.bot.llm.FallbackTransport(primary, transport(fallback), props.model().fallbackName());
    }

    /** Claude keeps the rules on its own; another model's answers are checked by the program. */
    private ModelTransport guarded(ModelTransport t) {
        String mode = props.model().guard() == null ? "enforce" : props.model().guard().strip();
        return switch (mode) {
            case "off" -> t;
            case "report" -> new io.github.pharaphara.raceradio.bot.llm.GuardedTransport(t, false);
            case "enforce" -> new io.github.pharaphara.raceradio.bot.llm.GuardedTransport(t, true);
            default -> throw new IllegalStateException("race-radio.model.guard must be enforce, report or off, not " + mode);
        };
    }

    private ModelTransport transport(String kind) {
        return switch (kind.strip()) {
            case "subscription" -> new ClaudeCodeTransport(props.model().claudeCommand(),
                    props.dataDir().resolve("claude"), Duration.ofMinutes(5));
            case "api" -> new AnthropicTransport(props.model().apiKey());
            case "openai", "openrouter" -> guarded(new io.github.pharaphara.raceradio.bot.llm.OpenAiCompatibleTransport(
                    props.model().baseUrl(), props.model().endpointKey(), Duration.ofMinutes(3)));
            default -> throw new IllegalStateException("race-radio.model.transport must be subscription, api or openai, not " + kind);
        };
    }

    /** Reviews and answers as JSON with references to the facts, for models that need the help. */
    private boolean structured() {
        return "json".equalsIgnoreCase(props.model().format() == null ? "" : props.model().format().strip());
    }

    @Bean
    TelegramClient telegramClient() {
        return new TelegramClient(props.telegram().token());
    }

    /** The segment library: road, shared by every athlete. */
    @Bean
    io.github.pharaphara.raceradio.bot.strava.SegmentLibrary segmentLibrary(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new io.github.pharaphara.raceradio.bot.strava.SegmentLibrary(jdbc);
    }

    /** The administrator's own Strava application, which feeds the shared library. */
    @Bean
    io.github.pharaphara.raceradio.bot.strava.StravaClient stravaClient(io.github.pharaphara.raceradio.bot.store.AthleteDb administratorDb,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.client-id:}") String id,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.client-secret:}") String secret,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.refresh-token:}") String refresh) {
        return new io.github.pharaphara.raceradio.bot.strava.StravaClient(id, secret, refresh, administratorDb, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.StravaHarvest stravaHarvest(io.github.pharaphara.raceradio.bot.strava.StravaClient strava,
                                                                        io.github.pharaphara.raceradio.bot.store.AthleteDb administratorDb,
                                                                        Clock clock) {
        return new io.github.pharaphara.raceradio.bot.strava.StravaHarvest(strava, administratorDb, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.athlete.Athletes athletes(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new io.github.pharaphara.raceradio.bot.athlete.Athletes(jdbc);
    }

    /** Every athlete's coach, built from their row and what holds nobody's data. */
    @Bean
    io.github.pharaphara.raceradio.bot.athlete.AthleteRegistry athleteRegistry(
            io.github.pharaphara.raceradio.bot.athlete.Athletes athletes, org.springframework.jdbc.core.JdbcTemplate jdbc,
            TelegramClient telegram, Clock clock, ModelTransport transport,
            io.github.pharaphara.raceradio.bot.strava.SegmentLibrary library,
            io.github.pharaphara.raceradio.bot.strava.StravaHarvest harvest,
            @org.springframework.beans.factory.annotation.Value("${race-radio.writes:}") String writes,
            @org.springframework.beans.factory.annotation.Value("${race-radio.hr-strap-since:}") String strapSince) {
        var shared = new io.github.pharaphara.raceradio.bot.athlete.AthleteServices.Shared(jdbc, telegram, clock, props,
                SystemPrompt.fromClasspath(PROMPT), transport, library, harvest, structured(), writes, strapSince, null);
        return new io.github.pharaphara.raceradio.bot.athlete.AthleteRegistry(athletes, shared, a -> {
            if (a.administrator()) {
                return props.intervals().apiKey();
            }
            throw new IllegalStateException(a.label() + ": invited athletes' keys come with invitations");
        });
    }

    @Bean
    io.github.pharaphara.raceradio.bot.athlete.AthleteDispatcher athleteDispatcher(
            io.github.pharaphara.raceradio.bot.athlete.AthleteRegistry registry, TelegramClient telegram, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.athlete.AthleteDispatcher(registry, telegram, clock);
    }

    /**
     * Starts listening to Telegram on its own thread, only when a token is configured, and never in a
     * one-off run: Telegram serves a bot to one listener at a time, so a check run beside the service
     * would take its messages away. Every update goes to the dispatcher, which finds whose it is.
     */
    @Bean
    SmartLifecycle telegramListener(TelegramClient telegram, io.github.pharaphara.raceradio.bot.athlete.AthleteDispatcher dispatcher,
                                    @org.springframework.beans.factory.annotation.Value("${race-radio.once:}") String once) {
        return new SmartLifecycle() {
            private TelegramPoller poller;

            @Override
            public void start() {
                if (!once.isBlank()) {
                    return;
                }
                if (props.telegram().token() == null || props.telegram().token().isBlank()) {
                    LOG.warn("Telegram is not configured: TELEGRAM_TOKEN is needed to talk");
                    return;
                }
                poller = new TelegramPoller(telegram, dispatcher::handle);
                Thread.ofVirtual().name("telegram").start(poller);
            }

            @Override
            public void stop() {
                if (poller != null) {
                    poller.stop();
                }
            }

            @Override
            public boolean isRunning() {
                return poller != null;
            }
        };
    }
}
