package io.github.pharaphara.raceradio.bot.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Calls the Messages API through the official SDK.
 *
 * <p>The system prompt is sent as one block cached for an hour: it is long and identical from call
 * to call, so after the first call of the hour it is read from cache at a tenth of the input price.
 * Thinking is left at the model's default (adaptive); effort sets how hard it works.
 *
 * <p>With fallbacks on, a request the model declines is retried by the API itself on another
 * model, chosen according to the reason for declining. The answering model is reported back so the
 * ledger prices the call correctly.
 */
public final class AnthropicTransport implements ModelTransport {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;

    public AnthropicTransport(String apiKey) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    public String billing() {
        return "api";
    }

    @Override
    public ModelReply send(ModelRequest r, SystemPrompt system) {
        return call(base(r, system).addUserMessage(r.userContent()), r);
    }

    @Override
    public ModelReply sendWithImage(ModelRequest r, SystemPrompt system, byte[] image, String mediaType) {
        var source = com.anthropic.models.beta.messages.BetaBase64ImageSource.builder()
                .data(java.util.Base64.getEncoder().encodeToString(image))
                .mediaType("image/png".equals(mediaType)
                        ? com.anthropic.models.beta.messages.BetaBase64ImageSource.MediaType.IMAGE_PNG
                        : com.anthropic.models.beta.messages.BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                .build();
        return call(base(r, system).addUserMessageOfBetaContentBlockParams(List.of(
                com.anthropic.models.beta.messages.BetaContentBlockParam.ofImage(
                        com.anthropic.models.beta.messages.BetaImageBlockParam.builder().source(source).build()),
                com.anthropic.models.beta.messages.BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(r.userContent()).build()))), r);
    }

    private MessageCreateParams.Builder base(ModelRequest r, SystemPrompt system) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(r.model())
                .maxTokens(r.maxTokens())
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(system.text())
                        .cacheControl(BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build())
                        .build()))
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(r.effort())).build());
        if (r.fallbacks()) {
            b.addBeta(FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b;
    }

    private ModelReply call(MessageCreateParams.Builder b, ModelRequest r) {
        long started = System.nanoTime();
        BetaMessage m = client.beta().messages().create(b.build());
        long latency = (System.nanoTime() - started) / 1_000_000;

        boolean refused = m.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false);
        String text = m.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .strip();
        Usage usage = new Usage(m.usage().inputTokens(), m.usage().outputTokens(),
                m.usage().cacheCreationInputTokens().orElse(0L), m.usage().cacheReadInputTokens().orElse(0L));
        return new ModelReply(text, m.model().asString(), m.stopReason().map(Object::toString).orElse(null), refused, usage, latency, null);
    }
}
