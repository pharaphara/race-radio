package io.github.pharaphara.raceradio.bot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A transport whose answers pass {@link ReplyGuard} before anyone reads them. In {@code enforce}
 * mode a failing answer is refused with an exception, which a {@link FallbackTransport} turns into
 * a second opinion from the model behind; in {@code report} mode it is only logged, to measure how
 * often a model would be caught.
 */
public final class GuardedTransport implements ModelTransport {

    private static final Logger LOG = LoggerFactory.getLogger(GuardedTransport.class);

    /** The answer broke a rule the program checks. */
    public static final class Rejected extends RuntimeException {
        Rejected(String model, List<String> problems, String answer) {
            super("answer from " + model + " rejected: " + String.join("; ", problems) + " | answer: " + answer);
        }
    }

    private final ModelTransport inner;
    private final boolean enforce;

    public GuardedTransport(ModelTransport inner, boolean enforce) {
        this.inner = inner;
        this.enforce = enforce;
    }

    @Override
    public String billing() {
        return inner.billing();
    }

    @Override
    public ModelReply send(ModelRequest r, SystemPrompt system) {
        return checked(r, system, inner.send(r, system));
    }

    @Override
    public ModelReply sendWithImage(ModelRequest r, SystemPrompt system, byte[] image, String mediaType) {
        return checked(r, system, inner.sendWithImage(r, system, image, mediaType));
    }

    private ModelReply checked(ModelRequest r, SystemPrompt system, ModelReply reply) {
        List<String> problems = ReplyGuard.problems(r, system, reply.text());
        if (problems.isEmpty()) {
            return reply;
        }
        String model = reply.answeringModel() == null ? r.model() : reply.answeringModel();
        LOG.warn("guard {} {} answer from {}: {}", enforce ? "rejected" : "flagged", r.purpose(), model, String.join("; ", problems));
        if (enforce) {
            throw new Rejected(model, problems, reply.text());
        }
        return reply;
    }
}
