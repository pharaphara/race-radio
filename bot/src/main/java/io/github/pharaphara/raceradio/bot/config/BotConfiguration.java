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
            "knowledge/strength.md", "knowledge/periodization.md", "knowledge/sessions.md");

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
    IntervalsWriter intervalsWriter(IntervalsClient client) {
        IntervalsWriter.Mode mode = props.dryRun() ? IntervalsWriter.Mode.DRY_RUN : IntervalsWriter.Mode.LIVE;
        LOG.info("Intervals.icu writes: {}", mode);
        return new IntervalsWriter(client, mode, line -> LOG.info("intervals.icu {}", line));
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
    SessionReview sessionReview(IntervalsClient intervals, IntervalsWriter writer, CoachModel model, DecisionLog decisions, Clock clock) {
        return new SessionReview(intervals, writer, model, decisions, props.model().reviewEffort(), clock);
    }

    @Bean
    ActivityWatcher activityWatcher(IntervalsClient intervals, ReviewedActivities reviewed, SessionReview review,
                                    TelegramClient telegram, Clock clock) {
        return new ActivityWatcher(intervals, reviewed, review, (activity, outcome) -> {
            if (props.telegram().allowedChatId() != 0) {
                telegram.send(props.telegram().allowedChatId(), outcome.message());
                for (byte[] image : outcome.images()) {
                    telegram.sendPhoto(props.telegram().allowedChatId(), image, null);
                }
            }
        }, props.schedule().watchFrom(), props.schedule().watchUntil(), clock);
    }

    @Bean
    TelegramClient telegramClient() {
        return new TelegramClient(props.telegram().token());
    }

    @Bean
    io.github.pharaphara.raceradio.bot.chat.Conversation conversation(CoachModel model, MorningCheck morning, DecisionLog decisions,
                                                                     io.github.pharaphara.raceradio.bot.store.Conversations memory, Clock clock) {
        return new io.github.pharaphara.raceradio.bot.chat.Conversation(model, morning, decisions, memory, clock);
    }

    @Bean
    CommandRouter commandRouter(TelegramClient telegram, MorningCheck morning, FeelStore feels, DecisionLog decisions,
                                Ledger ledger, Journal journal, CoachModel model, Clock clock,
                                io.github.pharaphara.raceradio.bot.chat.Conversation conversation,
                                io.github.pharaphara.raceradio.bot.store.Conversations memory,
                                io.github.pharaphara.raceradio.bot.strength.BoardReader boards,
                                io.github.pharaphara.raceradio.bot.plan.PlanCommands planCommands,
                                io.github.pharaphara.raceradio.bot.plan.Adapter adapter,
                                io.github.pharaphara.raceradio.bot.merge.Merger merger) {
        return new CommandRouter(telegram, props.telegram().allowedChatId(), morning, feels, decisions, ledger, journal,
                model, clock, props.zone(), props.dryRun(), conversation, memory, boards, planCommands, adapter, merger);
    }

    @Bean
    io.github.pharaphara.raceradio.bot.merge.Merger merger(IntervalsClient intervals, IntervalsWriter writer) {
        return new io.github.pharaphara.raceradio.bot.merge.Merger(intervals, writer);
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
