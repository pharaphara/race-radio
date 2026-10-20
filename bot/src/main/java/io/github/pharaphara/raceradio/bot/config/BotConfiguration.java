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
    IntervalsClient intervalsClient() {
        return new IntervalsClient(new IntervalsCredentials(props.intervals().athleteId(), props.intervals().apiKey()));
    }

    @Bean
    IntervalsWriter intervalsWriter(IntervalsClient client,
                                    @org.springframework.beans.factory.annotation.Value("${race-radio.writes:}") String writes) {
        // RACE_RADIO_WRITES opens writes kind by kind; without it, RACE_RADIO_DRY_RUN=false opens them all.
        java.util.Set<IntervalsWriter.Write> allowed = !writes.isBlank() ? IntervalsWriter.Write.parse(writes)
                : props.dryRun() ? java.util.EnumSet.noneOf(IntervalsWriter.Write.class) : java.util.EnumSet.allOf(IntervalsWriter.Write.class);
        LOG.info("Intervals.icu writes: {}", allowed.isEmpty() ? "DRY_RUN" : allowed);
        return new IntervalsWriter(client, allowed, line -> LOG.info("intervals.icu {}", line));
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

    @Bean
    Journal journal() {
        return new Journal(props.dataDir().resolve("llm"));
    }

    @Bean
    CoachModel coachModel(ModelTransport transport, Ledger ledger, Journal journal, Clock clock) {
        return new CoachModel(transport, SystemPrompt.fromClasspath(PROMPT), ledger, journal,
                new Pricing(props.model().prices()), props.model().name(), props.model().fallbacks(),
                props.model().monthlyBudget(), clock, props.zone());
    }

    @Bean
    MorningCheck morningCheck(IntervalsClient intervals, IntervalsWriter writer, CoachModel model,
                              FeelStore feels, DecisionLog decisions, Clock clock) {
        return new MorningCheck(intervals, writer, model, feels, decisions, props.temperament(), props.model().morningEffort(), clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.commands.QuickReplies quickReplies() {
        return new io.github.pharaphara.raceradio.bot.commands.QuickReplies();
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.StravaClient stravaClient(io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.client-id:}") String id,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.client-secret:}") String secret,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.refresh-token:}") String refresh) {
        return new io.github.pharaphara.raceradio.bot.strava.StravaClient(id, secret, refresh, jdbc, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.StravaHarvest stravaHarvest(io.github.pharaphara.raceradio.bot.strava.StravaClient strava,
                                                                        io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.strava.StravaHarvest(strava, jdbc, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.Segments segments(io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc,
                                                              io.github.pharaphara.raceradio.bot.strava.StravaHarvest harvest,
                                                              TelegramClient telegram,
                                                              io.github.pharaphara.raceradio.bot.strava.LocalSegments local,
                                                              io.github.pharaphara.raceradio.bot.strava.SegmentLibrary library,
                                                              io.github.pharaphara.raceradio.bot.archive.StreamCache streams, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.strava.Segments(jdbc, harvest, telegram, props.telegram().allowedChatId())
                .withLocal(local, library, streams, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.LocalSegments localSegments(io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc,
                                                                        io.github.pharaphara.raceradio.bot.strava.SegmentLibrary library,
                                                                        io.github.pharaphara.raceradio.bot.archive.StreamCache streams, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.strava.LocalSegments(jdbc, library, streams, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.SegmentLibrary segmentLibrary(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new io.github.pharaphara.raceradio.bot.strava.SegmentLibrary(jdbc);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.Calibration calibration(io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc,
                                                                    io.github.pharaphara.raceradio.bot.strava.SegmentLibrary library,
                                                                    io.github.pharaphara.raceradio.bot.archive.StreamCache streams) {
        return new io.github.pharaphara.raceradio.bot.strava.Calibration(jdbc, library, streams);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.archive.StreamCache streamCache(IntervalsClient intervals) {
        return new io.github.pharaphara.raceradio.bot.archive.StreamCache(intervals, props.dataDir().resolve("streams"));
    }

    @Bean
    io.github.pharaphara.raceradio.bot.archive.Archive archive(io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc, IntervalsClient intervals,
                                                             io.github.pharaphara.raceradio.bot.archive.StreamCache streams, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.archive.Archive(jdbc, intervals, streams, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.archive.Trends trends(io.github.pharaphara.raceradio.bot.archive.Archive archive,
                                                           TelegramClient telegram, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${race-radio.hr-strap-since:}") String strapSince) {
        return new io.github.pharaphara.raceradio.bot.archive.Trends(archive, telegram, props.telegram().allowedChatId(), clock)
                .withStrapSince(strapSince.isBlank() ? null : java.time.LocalDate.parse(strapSince));
    }

    @Bean
    SessionReview sessionReview(IntervalsClient intervals, IntervalsWriter writer, CoachModel model, DecisionLog decisions, Clock clock,
                                io.github.pharaphara.raceradio.bot.archive.StreamCache streams,
                                io.github.pharaphara.raceradio.bot.archive.Archive archive,
                                io.github.pharaphara.raceradio.bot.strava.LocalSegments segments) {
        return new SessionReview(intervals, writer, model, decisions, props.model().reviewEffort(), clock, streams::get)
                .withArchive(archive).withSegments(segments).structured(structured());
    }

    /** Reviews and answers as JSON with references to the facts, for models that need the help. */
    private boolean structured() {
        return "json".equalsIgnoreCase(props.model().format() == null ? "" : props.model().format().strip());
    }

    @Bean
    ActivityWatcher activityWatcher(IntervalsClient intervals, ReviewedActivities reviewed, SessionReview review,
                                    TelegramClient telegram, Clock clock, io.github.pharaphara.raceradio.bot.commands.QuickReplies replies) {
        return new ActivityWatcher(intervals, reviewed, review, (activity, outcome) -> {
            if (props.telegram().allowedChatId() != 0) {
                // Charts first, so the buttons stay under the text, at the bottom of the chat.
                for (byte[] image : outcome.images()) {
                    telegram.sendPhoto(props.telegram().allowedChatId(), image, null);
                }
                telegram.sendRichWithButtons(props.telegram().allowedChatId(), outcome.message(),
                        replies.rows(java.util.List.of("Analyse plus détaillée", "Que faire demain ?")));
            }
        }, props.schedule().watchFrom(), props.schedule().watchUntil(), clock);
    }

    @Bean
    TelegramClient telegramClient() {
        return new TelegramClient(props.telegram().token());
    }

    @Bean
    io.github.pharaphara.raceradio.bot.chat.Conversation conversation(CoachModel model, MorningCheck morning, DecisionLog decisions,
                                                                     io.github.pharaphara.raceradio.bot.store.Conversations memory, Clock clock,
                                                                     io.github.pharaphara.raceradio.bot.archive.Archive archive,
                                                                     io.github.pharaphara.raceradio.bot.archive.Trends trends) {
        return new io.github.pharaphara.raceradio.bot.chat.Conversation(model, morning, decisions, memory, clock,
                new io.github.pharaphara.raceradio.bot.archive.ArchiveHistory(archive, trends)).structured(structured());
    }

    @Bean
    CommandRouter commandRouter(TelegramClient telegram, MorningCheck morning, FeelStore feels, DecisionLog decisions,
                                Ledger ledger, Journal journal, CoachModel model, Clock clock, IntervalsWriter writer,
                                io.github.pharaphara.raceradio.bot.chat.Conversation conversation,
                                io.github.pharaphara.raceradio.bot.store.Conversations memory,
                                io.github.pharaphara.raceradio.bot.strength.BoardReader boards,
                                io.github.pharaphara.raceradio.bot.plan.PlanCommands planCommands,
                                io.github.pharaphara.raceradio.bot.plan.Adapter adapter,
                                io.github.pharaphara.raceradio.bot.merge.Merger merger,
                                io.github.pharaphara.raceradio.bot.plan.SessionCreator sessions,
                                io.github.pharaphara.raceradio.bot.archive.Trends trends,
                                io.github.pharaphara.raceradio.bot.strava.Segments segments,
                                io.github.pharaphara.raceradio.bot.commands.QuickReplies replies) {
        return new CommandRouter(telegram, props.telegram().allowedChatId(), morning, feels, decisions, ledger, journal,
                model, clock, props.zone(), writer.allowed().isEmpty(), conversation, memory, boards, planCommands, adapter, merger, sessions, trends, segments)
                .withReplies(replies);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.plan.SessionCreator sessionCreator(CoachModel model,
                                                                        io.github.pharaphara.raceradio.bot.plan.Planner planner,
                                                                        IntervalsWriter writer,
                                                                        io.github.pharaphara.raceradio.bot.store.Plans plans,
                                                                        DecisionLog decisions,
                                                                        io.github.pharaphara.raceradio.bot.store.Conversations memory,
                                                                        io.github.pharaphara.raceradio.bot.archive.Archive archive,
                                                                        TelegramClient telegram, Clock clock,
                                                                        io.github.pharaphara.raceradio.bot.commands.QuickReplies replies) {
        return new io.github.pharaphara.raceradio.bot.plan.SessionCreator(model, planner, writer, plans, decisions, memory,
                archive, telegram, props.telegram().allowedChatId(), clock).withReplies(replies);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.merge.Merger merger(IntervalsClient intervals, IntervalsWriter writer,
                                                         io.github.pharaphara.raceradio.bot.archive.Archive archive) {
        return new io.github.pharaphara.raceradio.bot.merge.Merger(intervals, writer, archive::invalidate);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.plan.Planner planner(IntervalsClient intervals, IntervalsWriter writer,
                                                          io.github.pharaphara.raceradio.bot.store.Plans plans, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.plan.Planner(intervals, writer, plans,
                props.athlete() == null ? null : props.athlete().swimCssSeconds(),
                props.athlete() == null ? 5 : props.athlete().rampPerWeek(), clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.plan.Adapter adapter(IntervalsClient intervals, IntervalsWriter writer,
                                                          io.github.pharaphara.raceradio.bot.store.Plans plans,
                                                          io.github.pharaphara.raceradio.bot.plan.Planner planner,
                                                          io.github.pharaphara.raceradio.bot.store.AthleteDb jdbc, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.plan.Adapter(intervals, writer, plans, planner, jdbc, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.plan.PlanCommands planCommands(io.github.pharaphara.raceradio.bot.store.Plans plans,
                                                                    io.github.pharaphara.raceradio.bot.plan.Planner planner, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.plan.PlanCommands(plans, planner, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strength.BoardReader boardReader(CoachModel model, IntervalsClient intervals,
                                                                       IntervalsWriter writer, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.strength.BoardReader(model, intervals, writer, props.dataDir().resolve("photos"), clock);
    }

    /**
     * Starts listening to Telegram on its own thread, only when a token and a chat are configured,
     * and never in a one-off run: Telegram serves a bot to one listener at a time, so a check run
     * beside the service would take its messages away.
     */
    @Bean
    SmartLifecycle telegramListener(TelegramClient telegram, CommandRouter router,
                                    @org.springframework.beans.factory.annotation.Value("${race-radio.once:}") String once) {
        return new SmartLifecycle() {
            private TelegramPoller poller;

            @Override
            public void start() {
                if (!once.isBlank()) {
                    return;
                }
                if (props.telegram().token() == null || props.telegram().token().isBlank() || props.telegram().allowedChatId() == 0) {
                    LOG.warn("Telegram is not configured: TELEGRAM_TOKEN and TELEGRAM_ALLOWED_CHAT_ID are needed to talk");
                    return;
                }
                poller = new TelegramPoller(telegram, props.telegram().allowedChatId(), router::handle);
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
