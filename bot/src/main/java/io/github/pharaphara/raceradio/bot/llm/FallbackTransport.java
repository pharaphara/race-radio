package io.github.pharaphara.raceradio.bot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A primary model and a second one behind it: a free open model first, say, and Claude when it is
 * down, out of quota, or declines. The second is asked the very same request under its own model
 * name, and the reply names the model that actually answered, so the ledger and the journal say
 * which one wrote each message.
 */
public final class FallbackTransport implements ModelTransport {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackTransport.class);

    private final ModelTransport primary;
    private final ModelTransport secondary;
    private final String secondaryModel;

    public FallbackTransport(ModelTransport primary, ModelTransport secondary, String secondaryModel) {
        this.primary = primary;
        this.secondary = secondary;
        this.secondaryModel = secondaryModel;
    }

    @Override
    public String billing() {
        return primary.billing();
    }

    @Override
    public ModelReply send(ModelRequest r, SystemPrompt system) {
        try {
            ModelReply reply = primary.send(r, system);
            if (!reply.refused() && !reply.text().isBlank()) {
                return reply;
            }
            LOG.warn("model {} gave no usable answer, asking {}", r.model(), secondaryModel);
        } catch (RuntimeException e) {
            LOG.warn("model {} failed ({}), asking {}", r.model(), e.getMessage(), secondaryModel);
        }
        return named(secondary.send(onSecondary(r), system));
    }

    /** The second model's reply always names it, or the ledger would price it as the first. */
    private ModelReply named(ModelReply reply) {
        return reply.answeringModel() != null ? reply : new ModelReply(reply.text(), secondaryModel, reply.stopReason(),
                reply.refused(), reply.usage(), reply.latencyMs(), reply.reportedCostUsd());
    }

    @Override
    public ModelReply sendWithImage(ModelRequest r, SystemPrompt system, byte[] image, String mediaType) {
        try {
            ModelReply reply = primary.sendWithImage(r, system, image, mediaType);
            if (!reply.refused() && !reply.text().isBlank()) {
                return reply;
            }
            LOG.warn("model {} gave no usable answer on an image, asking {}", r.model(), secondaryModel);
        } catch (RuntimeException e) {
            LOG.warn("model {} failed on an image ({}), asking {}", r.model(), e.getMessage(), secondaryModel);
        }
        return named(secondary.sendWithImage(onSecondary(r), system, image, mediaType));
    }

    private ModelRequest onSecondary(ModelRequest r) {
        return new ModelRequest(r.purpose(), secondaryModel, r.effort(), r.maxTokens(), r.fallbacks(),
                r.promptVersion(), r.userContent(), r.imageSha256());
    }
}
