package io.github.pharaphara.raceradio.bot.athlete;

import org.slf4j.MDC;

import java.util.function.Supplier;

/**
 * Whose work a log line is about, without anyone's name: the athlete's number goes in the logging
 * context ({@value #KEY}) for the length of a task, and the log pattern prints it on every line, from
 * whichever class writes it. Set where work for an athlete starts: a chat's queue, a scheduled job,
 * an archive filled in the background, a one-off run.
 */
public final class AthleteLog {

    public static final String KEY = "athlete";
    /** Work that belongs to no athlete yet: a stranger's message, a check-in under way. */
    public static final String NOBODY = "new";

    private AthleteLog() {
    }

    public static void run(AthleteId athlete, Runnable task) {
        call(athlete, () -> {
            task.run();
            return null;
        });
    }

    public static <T> T call(AthleteId athlete, Supplier<T> task) {
        return within(athlete == null ? NOBODY : "#" + athlete.value(), task);
    }

    static <T> T within(String value, Supplier<T> task) {
        String before = MDC.get(KEY);
        MDC.put(KEY, value);
        try {
            return task.get();
        } finally {
            if (before == null) {
                MDC.remove(KEY);
            } else {
                MDC.put(KEY, before);
            }
        }
    }
}
