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

    private final String once;

    public Schedules(RaceRadioProperties props, MorningCheck morning, TelegramClient telegram, Journal journal, ActivityWatcher watcher,
                     @org.springframework.beans.factory.annotation.Value("${race-radio.once:}") String once) {
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
        // The watcher checks its own daytime window; outside it a poll costs nothing.
        registrar.addFixedDelayTask(this::watch, java.time.Duration.ofMinutes(props.schedule().watchEveryMinutes()));
    }

    void morningCall() {
        try {
            MorningCheck.Outcome o = morning.run(true);
            if (props.telegram().allowedChatId() != 0) {
                telegram.send(props.telegram().allowedChatId(), o.message());
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
            }
        } catch (RuntimeException e) {
            LOG.warn("looking for new activities failed: {}", e.getMessage());
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
