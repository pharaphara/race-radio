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
        return switch (props.model().transport()) {
            case "subscription" -> new ClaudeCodeTransport(props.model().claudeCommand(),
                    props.dataDir().resolve("claude"), Duration.ofMinutes(5));
            case "api" -> new AnthropicTransport(props.model().apiKey());
            default -> throw new IllegalStateException("race-radio.model.transport must be subscription or api");
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
    io.github.pharaphara.raceradio.bot.strava.StravaClient stravaClient(org.springframework.jdbc.core.JdbcTemplate jdbc, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.client-id:}") String id,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.client-secret:}") String secret,
            @org.springframework.beans.factory.annotation.Value("${race-radio.strava.refresh-token:}") String refresh) {
        return new io.github.pharaphara.raceradio.bot.strava.StravaClient(id, secret, refresh, jdbc, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.StravaHarvest stravaHarvest(io.github.pharaphara.raceradio.bot.strava.StravaClient strava,
                                                                        org.springframework.jdbc.core.JdbcTemplate jdbc, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.strava.StravaHarvest(strava, jdbc, clock);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.strava.Segments segments(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                              io.github.pharaphara.raceradio.bot.strava.StravaHarvest harvest,
                                                              TelegramClient telegram) {
        return new io.github.pharaphara.raceradio.bot.strava.Segments(jdbc, harvest, telegram, props.telegram().allowedChatId());
    }

    @Bean
    io.github.pharaphara.raceradio.bot.archive.StreamCache streamCache(IntervalsClient intervals) {
        return new io.github.pharaphara.raceradio.bot.archive.StreamCache(intervals, props.dataDir().resolve("streams"));
    }

    @Bean
    io.github.pharaphara.raceradio.bot.archive.Archive archive(org.springframework.jdbc.core.JdbcTemplate jdbc, IntervalsClient intervals,
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
                                io.github.pharaphara.raceradio.bot.archive.Archive archive) {
        return new SessionReview(intervals, writer, model, decisions, props.model().reviewEffort(), clock, streams::get).withArchive(archive);
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
                new io.github.pharaphara.raceradio.bot.archive.ArchiveHistory(archive, trends));
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
                                                          org.springframework.jdbc.core.JdbcTemplate jdbc, Clock clock) {
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
