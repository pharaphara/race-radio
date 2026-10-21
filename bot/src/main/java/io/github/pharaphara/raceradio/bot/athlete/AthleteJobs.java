package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.plan.Adapter;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scheduled work for one athlete: the morning call, new activities, the Sunday recap, the
 * archive and the segments. {@code Schedules} runs each job for every active athlete in turn; a
 * failure is logged and reported to that athlete alone.
 */
public final class AthleteJobs {

    private static final Logger LOG = LoggerFactory.getLogger(AthleteJobs.class);

    private final AthleteServices s;
    private final TelegramClient telegram;
    private final ZoneId zone;

    public AthleteJobs(AthleteServices services, TelegramClient telegram, ZoneId zone) {
        this.s = services;
        this.telegram = telegram;
        this.zone = zone;
    }

    public void morningCall() {
        // Last night's figures into the archive, so the conversation can quote them all day.
        s.archive().refresh(3);
        try {
            MorningCheck.Outcome o = s.morning().run(true);
            Optional<Adapter.Result> adjusted = Optional.empty();
            try {
                adjusted = s.adapter().adapt(o.readiness().verdict());
            } catch (RuntimeException e) {
                LOG.warn("adjusting the day failed: {}", e.getMessage());
            }
            if (s.chatId() != 0) {
                boolean planned = s.archive().planned(LocalDate.now(zone), 1).stream().anyMatch(l -> l.contains(" WORKOUT "));
                List<Map<String, String>> rows = new ArrayList<>(s.replies().rows(List.of(
                        planned ? "Comment exécuter ma séance du jour ?" : "Propose-moi une séance pour aujourd'hui")));
                String text = o.message();
                if (adjusted.isPresent()) {
                    text += "\n\n" + adjusted.get().summary();
                    rows.addFirst(Map.of("Annuler l'ajustement", "undo:" + adjusted.get().batch()));
                }
                telegram.sendWithButtonRows(s.chatId(), text, rows);
            }
        } catch (RuntimeException e) {
            LOG.error("morning call failed: {}", e.getMessage());
            if (s.chatId() != 0) {
                telegram.send(s.chatId(), "Je n'ai pas pu faire le point ce matin : " + e.getMessage() + "\nRéessaie avec /aujourdhui.");
            }
        }
    }

    /** Times the segments of archived sessions not scanned yet; local only. */
    public void scanSegments(int max) {
        try {
            int n = s.segments().scanPending(max);
            if (n > 0) {
                LOG.info("segments: {} session(s) scanned", n);
            }
        } catch (RuntimeException e) {
            LOG.warn("segment scan failed: {}", e.getMessage());
        }
    }

    public void watch() {
        try {
            int n = s.watcher().poll();
            if (n > 0) {
                LOG.info("{} session(s) reviewed", n);
                // The review just cached the streams: archiving the new session costs two list calls.
                s.archive().refresh(3);
                scanSegments(20);
            }
        } catch (RuntimeException e) {
            LOG.warn("looking for new activities failed: {}", e.getMessage());
        }
    }

    /** The week's recap. Nothing is planned or published unasked: /semaine does that on request. */
    public void sundayEvening() {
        try {
            StringBuilder b = new StringBuilder(s.planner().recap());
            s.planner().feasibility().ifPresent(f -> b.append("\n\n").append(s.planner().describe(f)));
            b.append("\n\nLa semaine prochaine : /semaine pour que je te la propose.");
            if (s.chatId() != 0) {
                telegram.send(s.chatId(), b.toString());
            }
        } catch (RuntimeException e) {
            LOG.warn("Sunday recap failed: {}", e.getMessage());
        }
    }

    public void refreshArchive(int days) {
        try {
            s.archive().refresh(days);
        } catch (RuntimeException e) {
            LOG.warn("archive refresh failed: {}", e.getMessage());
        }
    }

    /** A year of history once, then the segments of all of it. */
    public void backfill() {
        try {
            s.archive().backfill();
        } catch (RuntimeException e) {
            LOG.warn("archive back-fill failed: {}", e.getMessage());
        }
        scanSegments(Integer.MAX_VALUE);
    }

    public void purgeJournal(int days) {
        int removed = s.journal().purgeOlderThan(days, LocalDate.now(zone));
        if (removed > 0) {
            LOG.info("journal: {} day file(s) older than {} days removed", removed, days);
        }
    }
}
