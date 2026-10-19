package io.github.pharaphara.raceradio.bot.commands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buttons that say something for the athlete: pressing one is the same as typing its text. Telegram
 * carries at most 64 bytes back from a button, so the text stays here and the button holds a number.
 * Only the most recent are kept; an old button simply asks to type the question.
 */
public final class QuickReplies {

    static final String PREFIX = "ask:";
    static final int KEPT = 300;
    /** Longest text shown on a button; longer suggestions are dropped rather than cut. */
    public static final int MAX_LABEL = 48;

    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, String> texts = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > KEPT;
        }
    };

    /** One row per text, as Telegram buttons: label to callback data. */
    public synchronized List<Map<String, String>> rows(List<String> questions) {
        return questions.stream().filter(q -> q != null && !q.isBlank() && q.strip().length() <= MAX_LABEL).distinct().limit(3)
                .map(q -> {
                    long id = ids.incrementAndGet();
                    texts.put(id, q.strip());
                    return Map.of(q.strip(), PREFIX + id);
                }).toList();
    }

    public boolean handles(String data) {
        return data != null && data.matches(PREFIX + "\\d+");
    }

    /** The text behind a pressed button, if still kept. */
    public synchronized Optional<String> text(String data) {
        return Optional.ofNullable(texts.get(Long.parseLong(data.substring(PREFIX.length()))));
    }
}
