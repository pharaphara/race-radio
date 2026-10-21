package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.archive.Archive;
import io.github.pharaphara.raceradio.bot.archive.ArchiveHistory;
import io.github.pharaphara.raceradio.bot.archive.StreamCache;
import io.github.pharaphara.raceradio.bot.archive.Trends;
import io.github.pharaphara.raceradio.bot.chat.Conversation;
import io.github.pharaphara.raceradio.bot.commands.CommandRouter;
import io.github.pharaphara.raceradio.bot.commands.QuickReplies;
import io.github.pharaphara.raceradio.bot.config.RaceRadioProperties;
import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.Journal;
import io.github.pharaphara.raceradio.bot.llm.ModelTransport;
import io.github.pharaphara.raceradio.bot.llm.Pricing;
import io.github.pharaphara.raceradio.bot.llm.SystemPrompt;
import io.github.pharaphara.raceradio.bot.merge.Merger;
import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.plan.Adapter;
import io.github.pharaphara.raceradio.bot.plan.PlanCommands;
import io.github.pharaphara.raceradio.bot.plan.Planner;
import io.github.pharaphara.raceradio.bot.plan.SessionCreator;
import io.github.pharaphara.raceradio.bot.review.ActivityWatcher;
import io.github.pharaphara.raceradio.bot.review.SessionReview;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import io.github.pharaphara.raceradio.bot.store.Conversations;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.FeelStore;
import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.bot.store.ReviewedActivities;
import io.github.pharaphara.raceradio.bot.strava.Calibration;
import io.github.pharaphara.raceradio.bot.strava.LocalSegments;
import io.github.pharaphara.raceradio.bot.strava.SegmentLibrary;
import io.github.pharaphara.raceradio.bot.strava.Segments;
import io.github.pharaphara.raceradio.bot.strava.StravaHarvest;
import io.github.pharaphara.raceradio.bot.strength.BoardReader;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the coach is for one athlete: their database view, their Intervals.icu client and
 * writer, their model and its ledger, their conversation, their drafts and buttons, their files.
 *
 * <p>Built from the athlete's row and the few things that hold nobody's data ({@link Shared}). Two
 * athletes share no instance of anything that keeps state, so nothing kept for one can reach the
 * other: not a draft in memory, not a cached stream, not a line of the journal.
 */
public final class AthleteServices {

    private static final Logger LOG = LoggerFactory.getLogger(AthleteServices.class);

    /**
     * What every athlete's coach is built with and that holds no athlete's data.
     *
     * @param intervalsApi  Intervals.icu's address; null for the real one, a local stand-in in tests
     */
    public record Shared(JdbcTemplate jdbc, TelegramClient telegram, Clock clock, RaceRadioProperties props, SystemPrompt prompt,
                         ModelTransport transport, SegmentLibrary library, StravaHarvest harvest, boolean structured,
                         String configuredWrites, String strapSince, java.net.URI intervalsApi) {
    }

    private final Athlete athlete;
    private final long chatId;
    private final Path dir;
    private final AthleteDb db;
    private final IntervalsClient intervals;
    private final IntervalsWriter writer;
    private final CoachModel model;
    private final Journal journal;
    private final Archive archive;
    private final MorningCheck morning;
    private final Adapter adapter;
    private final Planner planner;
    private final QuickReplies replies;
    private final ActivityWatcher watcher;
    private final LocalSegments segments;
    private final SessionReview review;
    private final SessionCreator sessions;
    private final Conversation conversation;
    private final Conversations memory;
    private final Trends trends;
    private final BoardReader boards;
    private final Calibration calibration;
    private final CommandRouter router;

    /**
     * @param intervalsKey  the athlete's Intervals.icu key, in clear, for this process only
     */
    public AthleteServices(Athlete athlete, String intervalsKey, Shared s) {
        this.athlete = athlete;
        this.chatId = athlete.chatId() == null ? 0 : athlete.chatId();
        RaceRadioProperties props = s.props();
        this.dir = props.dataDir().resolve("athletes").resolve(String.valueOf(athlete.id().value()));
        this.db = new AthleteDb(s.jdbc(), athlete.id());
        Clock clock = s.clock();

        IntervalsCredentials credentials = new IntervalsCredentials(athlete.intervalsAthleteId(), intervalsKey);
        this.intervals = s.intervalsApi() == null ? new IntervalsClient(credentials)
                : new IntervalsClient(credentials, java.net.http.HttpClient.newHttpClient(), s.intervalsApi());
        Set<IntervalsWriter.Write> allowed = writes(athlete, s);
        LOG.info("{}: Intervals.icu writes {}", athlete.label(), allowed.isEmpty() ? "DRY_RUN" : allowed);
        this.writer = new IntervalsWriter(intervals, allowed, line -> LOG.info("{} intervals.icu {}", athlete.label(), line));

        this.journal = new Journal(dir.resolve("llm"));
        Ledger ledger = new Ledger(db);
        BigDecimal budget = athlete.monthlyBudget() != null ? athlete.monthlyBudget() : props.model().monthlyBudget();
        this.model = new CoachModel(s.transport(), s.prompt(), ledger, journal, new Pricing(props.model().prices()),
                props.model().name(), props.model().fallbacks(), budget, clock, props.zone());

        FeelStore feels = new FeelStore(db);
        DecisionLog decisions = new DecisionLog(db);
        this.memory = new Conversations(db);
        Plans plans = new Plans(db);
        ReviewedActivities reviewed = new ReviewedActivities(db);

        StreamCache streams = new StreamCache(intervals, dir.resolve("streams"));
        this.archive = new Archive(db, intervals, streams, clock);
        this.trends = new Trends(archive, s.telegram(), chatId, clock)
                .withStrapSince(athlete.administrator() && !blank(s.strapSince()) ? LocalDate.parse(s.strapSince()) : null);
        this.morning = new MorningCheck(intervals, writer, model, feels, decisions, props.temperament(), props.model().morningEffort(), clock)
                .withSports(plans::sports);
        this.replies = new QuickReplies();

        this.segments = new LocalSegments(db, s.library(), streams, clock);
        Segments segmentCommands = new Segments(db, athlete.administrator() ? s.harvest() : null, s.telegram(), chatId)
                .withLocal(segments, s.library(), streams, clock);
        this.calibration = new Calibration(db, s.library(), streams);

        this.review = new SessionReview(intervals, writer, model, decisions, props.model().reviewEffort(), clock, streams::get)
                .withArchive(archive).withSegments(segments).structured(s.structured()).withSports(plans::sports);
        this.watcher = new ActivityWatcher(intervals, reviewed, review, (activity, outcome) -> {
            if (chatId != 0) {
                // Charts first, so the buttons stay under the text, at the bottom of the chat.
                for (byte[] image : outcome.images()) {
                    s.telegram().sendPhoto(chatId, image, null);
                }
                s.telegram().sendRichWithButtons(chatId, outcome.message(),
                        replies.rows(List.of("Analyse plus détaillée", "Que faire demain ?")));
            }
        }, props.schedule().watchFrom(), props.schedule().watchUntil(), clock);

        this.conversation = new Conversation(model, morning, decisions, memory, clock, new ArchiveHistory(archive, trends))
                .structured(s.structured()).withSports(plans::sports);
        // The swim threshold and the ramp in the configuration are the administrator's own.
        this.planner = new Planner(intervals, writer, plans,
                athlete.administrator() && props.athlete() != null ? props.athlete().swimCssSeconds() : null,
                props.athlete() == null ? 5 : props.athlete().rampPerWeek(), clock);
        this.adapter = new Adapter(intervals, writer, plans, planner, db, clock);
        PlanCommands planCommands = new PlanCommands(plans, planner, clock);
        this.sessions = new SessionCreator(model, planner, writer, plans, decisions, memory, archive, s.telegram(), chatId, clock)
                .withReplies(replies);
        Merger merger = new Merger(intervals, writer, archive::invalidate);
        this.boards = new BoardReader(model, intervals, writer, dir.resolve("photos"), clock);
        this.router = new CommandRouter(s.telegram(), chatId, morning, feels, decisions, ledger, journal, model, clock, props.zone(),
                allowed.isEmpty(), conversation, memory, boards, planCommands, adapter, merger, sessions, trends, segmentCommands)
                .withReplies(replies).withSports(plans::sports);
    }

    /**
     * The administrator writes what the configuration opens, as before; an invited athlete what
     * their row says, and nothing at all while the whole service is a dry run.
     */
    static Set<IntervalsWriter.Write> writes(Athlete athlete, Shared s) {
        boolean serviceDryRun = blank(s.configuredWrites()) && s.props().dryRun();
        if (athlete.administrator()) {
            return !blank(s.configuredWrites()) ? IntervalsWriter.Write.parse(s.configuredWrites())
                    : serviceDryRun ? EnumSet.noneOf(IntervalsWriter.Write.class) : EnumSet.allOf(IntervalsWriter.Write.class);
        }
        return serviceDryRun ? EnumSet.noneOf(IntervalsWriter.Write.class) : IntervalsWriter.Write.parse(athlete.writes());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public Athlete athlete() {
        return athlete;
    }

    public long chatId() {
        return chatId;
    }

    /** Where this athlete's files live: streams, journal, photos. */
    public Path dir() {
        return dir;
    }

    public AthleteDb db() {
        return db;
    }

    public IntervalsClient intervals() {
        return intervals;
    }

    public IntervalsWriter writer() {
        return writer;
    }

    public CoachModel model() {
        return model;
    }

    public Journal journal() {
        return journal;
    }

    public Archive archive() {
        return archive;
    }

    public MorningCheck morning() {
        return morning;
    }

    public Adapter adapter() {
        return adapter;
    }

    public Planner planner() {
        return planner;
    }

    public QuickReplies replies() {
        return replies;
    }

    public ActivityWatcher watcher() {
        return watcher;
    }

    public LocalSegments segments() {
        return segments;
    }

    public SessionReview review() {
        return review;
    }

    public SessionCreator sessions() {
        return sessions;
    }

    public Conversation conversation() {
        return conversation;
    }

    public Conversations memory() {
        return memory;
    }

    public Trends trends() {
        return trends;
    }

    public BoardReader boards() {
        return boards;
    }

    public Calibration calibration() {
        return calibration;
    }

    public CommandRouter router() {
        return router;
    }
}
