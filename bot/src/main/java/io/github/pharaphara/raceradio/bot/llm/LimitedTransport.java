package io.github.pharaphara.raceradio.bot.llm;

import java.util.concurrent.Semaphore;

/**
 * At most so many calls to the model at once, shared by every athlete: when a whole club comes
 * back from a ride, the reviews queue rather than all hitting the subscription together.
 */
public final class LimitedTransport implements ModelTransport {

    private final ModelTransport inner;
    private final Semaphore permits;

    public LimitedTransport(ModelTransport inner, Semaphore permits) {
        this.inner = inner;
        this.permits = permits;
    }

    @Override
    public String billing() {
        return inner.billing();
    }

    @Override
    public ModelReply send(ModelRequest r, SystemPrompt system) {
        acquire();
        try {
            return inner.send(r, system);
        } finally {
            permits.release();
        }
    }

    @Override
    public ModelReply sendWithImage(ModelRequest r, SystemPrompt system, byte[] image, String mediaType) {
        acquire();
        try {
            return inner.sendWithImage(r, system, image, mediaType);
        } finally {
            permits.release();
        }
    }

    private void acquire() {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the model");
        }
    }
}
