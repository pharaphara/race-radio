package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hands each Telegram update to the coach of the athlete whose chat it came from, and to nobody
 * else. A chat that belongs to no active athlete gets one short answer an hour at most, and nothing
 * of anyone's data: the bot is by invitation.
 *
 * <p>Each chat has its own queue on a virtual thread: one athlete's messages are handled in the order
 * sent, while a long answer to one never holds up another.
 */
public final class AthleteDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AthleteDispatcher.class);
    static final Duration QUIET = Duration.ofHours(1);
    static final String BY_INVITATION = "Ce coach fonctionne sur invitation. Si on t'a donné un code, envoie /start suivi du code.";

    private final AthleteRegistry registry;
    private final CheckIn checkIn;
    private final AdminCommands admin;
    private final TelegramClient telegram;
    private final Clock clock;
    private final Map<Long, Instant> answered = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> queues = new ConcurrentHashMap<>();

    public AthleteDispatcher(AthleteRegistry registry, CheckIn checkIn, AdminCommands admin, TelegramClient telegram, Clock clock) {
        this.registry = registry;
        this.checkIn = checkIn;
        this.admin = admin;
        this.telegram = telegram;
        this.clock = clock;
    }

    /** Queues the update on its chat's own line and returns at once. */
    public void handle(TelegramClient.Update u) {
        queues.computeIfAbsent(u.chatId(), chat -> Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat-", 0).factory()))
                .execute(() -> {
                    try {
                        handleNow(u);
                    } catch (RuntimeException e) {
                        LOG.warn("handling an update failed: {}", e.getMessage());
                    }
                });
    }

    /** Handles the update on the calling thread: for tests, and for the queues. */
    void handleNow(TelegramClient.Update u) {
        Optional<AthleteServices> athlete = registry.forChat(u.chatId());
        if (athlete.isPresent()) {
            if (athlete.get().athlete().administrator() && admin.handles(u)) {
                admin.handle(u, u.chatId());
                return;
            }
            athlete.get().router().handle(u);
            return;
        }
        if (checkIn.concerns(u)) {
            checkIn.handle(u);
            return;
        }
        stranger(u);
    }

    private void stranger(TelegramClient.Update u) {
        Instant now = clock.instant();
        Instant last = answered.get(u.chatId());
        if (last != null && last.plus(QUIET).isAfter(now)) {
            return;
        }
        answered.put(u.chatId(), now);
        LOG.info("an update from a chat that is no athlete's");
        if (u.isCallback()) {
            telegram.acknowledge(u.callbackId(), "");
        }
        telegram.send(u.chatId(), BY_INVITATION);
    }
}
