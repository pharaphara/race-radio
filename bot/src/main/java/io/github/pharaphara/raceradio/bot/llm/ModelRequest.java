package io.github.pharaphara.raceradio.bot.llm;

/**
 * The canonical description of one call: everything needed to rebuild the exact same request,
 * given the system prompt of the same version. This, not the HTTP bytes, is what the journal
 * keeps, and what a replay rebuilds from.
 *
 * @param imageSha256 hash of the image sent with the request, kept privately on disk beside the
 *                    journal; null when there is none
 */
public record ModelRequest(Purpose purpose, String model, String effort, long maxTokens,
                           boolean fallbacks, String promptVersion, String userContent, String imageSha256) {

    public ModelRequest(Purpose purpose, String model, String effort, long maxTokens, boolean fallbacks,
                        String promptVersion, String userContent) {
        this(purpose, model, effort, maxTokens, fallbacks, promptVersion, userContent, null);
    }
}
