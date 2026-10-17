package io.github.pharaphara.raceradio.bot.review;

import io.github.pharaphara.raceradio.bot.store.ReviewedActivities;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Looks for new activities, only during the day: the API has no webhook, so this is polling, and
 * there is no point polling while the athlete sleeps. Only activities started within the last
 * day and a half are considered, so a first start or a long outage never floods the chat with
 * old sessions.
 */
public final class ActivityWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityWatcher.class);
    static final int LOOKBACK_HOURS = 36;

    private final IntervalsClient intervals;
    private final ReviewedActivities reviewed;
    private final SessionReview review;
    private final BiConsumer<ActivitySummary, SessionReview.Outcome> deliver;
    private final LocalTime from;
    private final LocalTime until;
    private final Clock clock;

    public ActivityWatcher(IntervalsClient intervals, ReviewedActivities reviewed, SessionReview review,
                           BiConsumer<ActivitySummary, SessionReview.Outcome> deliver, LocalTime from, LocalTime until, Clock clock) {
        this.intervals = intervals;
        this.reviewed = reviewed;
        this.review = review;
        this.deliver = deliver;
        this.from = from;
        this.until = until;
        this.clock = clock;
    }

    public boolean inWindow() {
        LocalTime now = LocalTime.now(clock);
        return !now.isBefore(from) && now.isBefore(until);
    }

    /** One poll. Returns how many sessions were reviewed. */
    public int poll() {
        if (!inWindow()) {
            return 0;
        }
        LocalDateTime since = LocalDateTime.now(clock).minusHours(LOOKBACK_HOURS);
        List<ActivitySummary> fresh = intervals.activities(LocalDate.now(clock).minusDays(2), LocalDate.now(clock)).stream()
                .filter(a -> a.start() != null && a.start().isAfter(since))
                .filter(a -> a.movingSeconds() != null && a.movingSeconds() > 0)
                .filter(a -> !reviewed.contains(a.id()))
                .sorted(Comparator.comparing(ActivitySummary::start))
                .toList();
        int done = 0;
        for (ActivitySummary a : fresh) {
            try {
                SessionReview.Outcome o = review.review(a, true);
                reviewed.add(a.id(), clock.instant());
                deliver.accept(a, o);
                done++;
            } catch (RuntimeException e) {
                LOG.warn("review of activity {} failed, will retry on the next poll: {}", a.id(), e.getMessage());
            }
        }
        return done;
    }
}
