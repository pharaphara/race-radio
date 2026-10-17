package io.github.pharaphara.raceradio.bot.llm;

/** Sends one request. The real ones talk to Claude; tests use a stand-in. */
public interface ModelTransport {

    ModelReply send(ModelRequest request, SystemPrompt system);

    /**
     * How calls are paid for: {@code api} is billed per token, {@code subscription} draws on a
     * Claude subscription's usage limits and is not billed per call. Costs are recorded either way,
     * at list price, so that the two can be compared and the subscription's limits protected.
     */
    String billing();

    /** Sends one request with one image, such as a photo of a workout board. */
    default ModelReply sendWithImage(ModelRequest request, SystemPrompt system, byte[] image, String mediaType) {
        throw new UnsupportedOperationException("this transport does not take images");
    }
}
