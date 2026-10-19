package io.github.pharaphara.raceradio.bot.config;

import io.github.pharaphara.raceradio.bot.llm.Journal;
import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.review.ActivityWatcher;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/** The recurring jobs, at the times the configuration gives, in the athlete's time zone. */
@Component
public class Schedules implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(Schedules.class);

    private final RaceRadioProperties props;
    private final MorningCheck morning;
    private final TelegramClient telegram;
    private final Journal journal;
    private final ActivityWatcher watcher;
    private final io.github.pharaphara.raceradio.bot.plan.Planner planner;
    private final io.github.pharaphara.raceradio.bot.store.Plans plans;
    private final io.github.pharaphara.raceradio.bot.plan.Adapter adapter;
    private final io.github.pharaphara.raceradio.bot.archive.Archive archive;
    private final io.github.pharaphara.raceradio.bot.strava.StravaHarvest strava;

    private final String once;

    public Schedules(RaceRadioProperties props, MorningCheck morning, TelegramClient telegram, Journal journal, ActivityWatcher watcher,
                     @org.springframework.beans.factory.annotation.Value("${race-radio.once:}") String once,
                     io.github.pharaphara.raceradio.bot.plan.Planner planner, io.github.pharaphara.raceradio.bot.store.Plans plans,
                     io.github.pharaphara.raceradio.bot.plan.Adapter adapter,
                     io.github.pharaphara.raceradio.bot.archive.Archive archive,
                     io.github.pharaphara.raceradio.bot.strava.StravaHarvest strava) {
        this.strava = strava;
        this.archive = archive;
        this.adapter = adapter;
        this.planner = planner;
        this.plans = plans;
        this.once = once;
        this.props = props;
        this.morning = morning;
        this.telegram = telegram;
        this.journal = journal;
        this.watcher = watcher;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!once.isBlank()) {
            // A one-off run does its single job and nothing else: no schedule, no watcher.
            return;
        }
        registrar.addCronTask(new CronTask(this::morningCall, daily(props.schedule().morningAt())));
        registrar.addCronTask(new CronTask(this::purgeJournal, daily(LocalTime.of(3, 30))));
        registrar.addCronTask(new CronTask(this::sundayEvening, new CronTrigger("0 0 19 * * SUN", props.zone())));
        // The watcher checks its own daytime window; outside it a poll costs nothing.
        registrar.addFixedDelayTask(this::watch, java.time.Duration.ofMinutes(props.schedule().watchEveryMinutes()));
        // The local archive: a year of history once, in the background, then the last month every night.
        registrar.addCronTask(new CronTask(() -> archive.refresh(30), daily(LocalTime.of(3, 45))));
        Thread.ofVirtual().name("archive-backfill").start(archive::backfill);
        // Strava segments, harvested within the read quota: a step every quarter hour, idle once done.
        registrar.addFixedDelayTask(strava::run, java.time.Duration.ofMinutes(15));
    }

    void morningCall() {
        // Last night's figures into the archive, so the conversation can quote them all day.
        archive.refresh(3);
        try {
            MorningCheck.Outcome o = morning.run(true);
            java.util.Optional<io.github.pharaphara.raceradio.bot.plan.Adapter.Result> adjusted = java.util.Optional.empty();
            try {
                adjusted = adapter.adapt(o.readiness().verdict());
            } catch (RuntimeException e) {
                LOG.warn("adjusting the day failed: {}", e.getMessage());
            }
            if (props.telegram().allowedChatId() != 0) {
                if (adjusted.isPresent()) {
                    telegram.sendWithButtons(props.telegram().allowedChatId(), o.message() + "\n\n" + adjusted.get().summary(),
                            java.util.Map.of("Annuler l'ajustement", "undo:" + adjusted.get().batch()));
                } else {
                    telegram.send(props.telegram().allowedChatId(), o.message());
                }
            }
        } catch (RuntimeException e) {
            LOG.error("morning call failed: {}", e.getMessage());
            if (props.telegram().allowedChatId() != 0) {
                telegram.send(props.telegram().allowedChatId(),
                        "Je n'ai pas pu faire le point ce matin : " + e.getMessage() + "\nRéessaie avec /aujourdhui.");
            }
        }
    }

    void watch() {
        try {
            int n = watcher.poll();
            if (n > 0) {
                LOG.info("{} session(s) reviewed", n);
                // The review just cached the streams: archiving the new session costs two list calls.
                archive.refresh(3);
            }
        } catch (RuntimeException e) {
            LOG.warn("looking for new activities failed: {}", e.getMessage());
        }
    }

    /** The week's recap. Nothing is planned or published unasked: /semaine does that on request. */
    void sundayEvening() {
        try {
            StringBuilder b = new StringBuilder(planner.recap());
            planner.feasibility().ifPresent(f -> b.append("\n\n").append(planner.describe(f)));
            b.append("\n\nLa semaine prochaine : /semaine pour que je te la propose.");
            if (props.telegram().allowedChatId() != 0) {
                telegram.send(props.telegram().allowedChatId(), b.toString());
            }
        } catch (RuntimeException e) {
            LOG.warn("Sunday recap failed: {}", e.getMessage());
        }
    }

    void purgeJournal() {
        int removed = journal.purgeOlderThan(props.model().journalDays(), LocalDate.now(props.zone()));
        if (removed > 0) {
            LOG.info("journal: {} day file(s) older than {} days removed", removed, props.model().journalDays());
        }
    }

    private CronTrigger daily(LocalTime at) {
        return new CronTrigger("0 " + at.getMinute() + " " + at.getHour() + " * * *", props.zone());
    }
}
