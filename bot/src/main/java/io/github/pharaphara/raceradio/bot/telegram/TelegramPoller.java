package io.github.pharaphara.raceradio.bot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Listens for messages with long polling, so no public endpoint is needed, and hands each one to
 * the handler, which decides whose it is: the athletes' dispatcher.
 */
public final class TelegramPoller implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramPoller.class);
    private static final int WAIT_SECONDS = 50;

    private final TelegramClient telegram;
    private final Consumer<TelegramClient.Update> handler;
    private volatile boolean running = true;

    public TelegramPoller(TelegramClient telegram, Consumer<TelegramClient.Update> handler) {
        this.telegram = telegram;
        this.handler = handler;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        long offset = 0;
        int failures = 0;
        while (running) {
            try {
                for (TelegramClient.Update u : telegram.updates(offset, WAIT_SECONDS)) {
                    offset = u.updateId() + 1;
                    try {
                        handler.accept(u);
                    } catch (RuntimeException e) {
                        LOG.warn("handling an update failed: {}", e.getMessage());
                    }
                }
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                LOG.warn("polling Telegram failed ({} in a row): {}", failures, e.getMessage());
                sleep(Math.min(300, 5L * failures));
            }
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
