package io.github.pharaphara.raceradio.bot.config;

import io.github.pharaphara.raceradio.bot.athlete.AthleteJobs;
import io.github.pharaphara.raceradio.bot.athlete.AthleteRegistry;
import io.github.pharaphara.raceradio.bot.athlete.AthleteServices;
import io.github.pharaphara.raceradio.bot.strava.StravaHarvest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.function.Consumer;

/**
 * The recurring jobs, at the times the configuration gives, in the service's time zone. Each job
 * runs for every active athlete in turn, each in its own try: one athlete's failure stays theirs.
 * The Strava harvest is the administrator's own application, and theirs alone.
 */
@Component
public class Schedules implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(Schedules.class);

    private final RaceRadioProperties props;
    private final AthleteRegistry registry;
    private final StravaHarvest strava;
    private final io.github.pharaphara.raceradio.bot.strava.ZoneHarvest zone;
    private final io.github.pharaphara.raceradio.bot.strava.SegmentLibrary library;
    private final String once;

    public Schedules(RaceRadioProperties props, AthleteRegistry registry, StravaHarvest strava,
                     io.github.pharaphara.raceradio.bot.strava.ZoneHarvest zone,
                     io.github.pharaphara.raceradio.bot.strava.SegmentLibrary library,
                     @org.springframework.beans.factory.annotation.Value("${race-radio.once:}") String once) {
        this.zone = zone;
        this.library = library;
        this.props = props;
        this.registry = registry;
        this.strava = strava;
        this.once = once;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!once.isBlank()) {
            // A one-off run does its single job and nothing else: no schedule, no watcher.
            return;
        }
        registrar.addCronTask(new CronTask(() -> each("morning call", AthleteJobs::morningCall), daily(props.schedule().morningAt())));
        registrar.addCronTask(new CronTask(() -> each("journal purge", j -> j.purgeJournal(props.model().journalDays())),
                daily(LocalTime.of(3, 30))));
        registrar.addCronTask(new CronTask(() -> each("Sunday recap", AthleteJobs::sundayEvening),
                new CronTrigger("0 0 19 * * SUN", props.zone())));
        // The watcher checks its own daytime window; outside it a poll costs nothing.
        registrar.addFixedDelayTask(() -> each("watch", AthleteJobs::watch),
                java.time.Duration.ofMinutes(props.schedule().watchEveryMinutes()));
        // The local archive: a year of history once, in the background, then the last month every night.
        registrar.addCronTask(new CronTask(() -> each("archive refresh", j -> j.refreshArchive(30)), daily(LocalTime.of(3, 45))));
        Thread.ofVirtual().name("archive-backfill").start(() -> each("archive back-fill", AthleteJobs::backfill));
        registrar.addCronTask(new CronTask(() -> {
            // Segments added to the shared library during the day are timed on everyone's archive tonight.
            library.reload();
            each("segment scan", j -> j.scanSegments(500));
        }, daily(LocalTime.of(4, 15))));
        // Strava segments, harvested within the read quota: a step every quarter hour, idle once done.
        // An area asked with /segment zone goes first, then the traces of every library segment.
        registrar.addFixedDelayTask(() -> {
            try {
                zone.step();
            } catch (RuntimeException e) {
                LOG.warn("strava zone step failed: {}", e.getMessage());
            }
            strava.run();
        }, java.time.Duration.ofMinutes(15));
    }

    /** The job for every active athlete, one after the other. */
    void each(String name, Consumer<AthleteJobs> job) {
        for (AthleteServices s : registry.active()) {
            try {
                job.accept(registry.jobs(s));
            } catch (RuntimeException e) {
                LOG.warn("{}: {} failed: {}", s.athlete().label(), name, e.getMessage());
            }
        }
    }

    private CronTrigger daily(LocalTime at) {
        return new CronTrigger("0 " + at.getMinute() + " " + at.getHour() + " * * *", props.zone());
    }
}
