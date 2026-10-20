package io.github.pharaphara.raceradio.bot.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Calls any service that speaks the OpenAI chat completions format, with an API key: OpenRouter
 * (the default, and through it most open models, free ones included), OpenAI itself, Mistral,
 * Groq, or a model served locally by Ollama or vLLM.
 *
 * <p>Three differences between services are handled here and nowhere else:
 * <ul>
 *   <li>OpenAI's own endpoint wants {@code max_completion_tokens}; the others take {@code max_tokens}.</li>
 *   <li>Effort is passed as OpenRouter's {@code reasoning} object there, as {@code reasoning_effort}
 *       at OpenAI, and not at all elsewhere, where an unknown field may be refused.</li>
 *   <li>OpenRouter reports what a call cost; it is recorded as such, and a free model costs nothing.</li>
 * </ul>
 * Some open models write their reasoning between {@code <think>} tags in the answer: it is removed,
 * since only the answer is meant for the athlete.
 */
public final class OpenAiCompatibleTransport implements ModelTransport {

    public static final String OPENROUTER = "https://openrouter.ai/api/v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final Duration timeout;

    public OpenAiCompatibleTransport(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), timeout);
    }

    OpenAiCompatibleTransport(String baseUrl, String apiKey, HttpClient http, Duration timeout) {
        String base = baseUrl == null || baseUrl.isBlank() ? OPENROUTER : baseUrl.strip();
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = apiKey;
        this.http = http;
        this.timeout = timeout;
    }

    @Override
    public String billing() {
        return "api";
    }

    @Override
    public ModelReply send(ModelRequest r, SystemPrompt system) {
        return call(body(r, system, JSON.getNodeFactory().textNode(r.userContent())), r);
    }

    @Override
    public ModelReply sendWithImage(ModelRequest r, SystemPrompt system, byte[] image, String mediaType) {
        ArrayNode content = JSON.createArrayNode();
        content.addObject().put("type", "text").put("text", r.userContent());
        content.addObject().put("type", "image_url").putObject("image_url")
                .put("url", "data:" + (mediaType == null ? "image/jpeg" : mediaType) + ";base64," + Base64.getEncoder().encodeToString(image));
        return call(body(r, system, content), r);
    }

    ObjectNode body(ModelRequest r, SystemPrompt system, JsonNode userContent) {
        ObjectNode b = JSON.createObjectNode();
        b.put("model", r.model());
        String host = endpoint.getHost();
        b.put(host.equals("api.openai.com") ? "max_completion_tokens" : "max_tokens", r.maxTokens());
        if (r.effort() != null) {
            if (host.endsWith("openrouter.ai")) {
                b.putObject("reasoning").put("effort", r.effort());
                b.putObject("usage").put("include", true);
            } else if (host.equals("api.openai.com")) {
                b.put("reasoning_effort", r.effort());
            }
        }
        ArrayNode messages = b.putArray("messages");
        messages.addObject().put("role", "system").put("content", system.text());
        messages.addObject().put("role", "user").set("content", userContent);
        return b;
    }

    private ModelReply call(ObjectNode body, ModelRequest r) {
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "race-radio")
                .header("X-Title", "race-radio")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey.strip());
        }
        long started = System.nanoTime();
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("model endpoint unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the model", e);
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        if (res.statusCode() / 100 != 2) {
            String detail = res.body() == null ? "" : res.body();
            throw new IllegalStateException("model endpoint answered HTTP " + res.statusCode() + ": "
                    + (detail.length() > 300 ? detail.substring(0, 300) + "…" : detail));
        }
        return parse(JSON.readTree(res.body()), r.model(), latency);
    }

    static ModelReply parse(JsonNode root, String requested, long latency) {
        if (root.has("error")) {
            // OpenRouter can answer 200 with an error from the provider behind it.
            throw new IllegalStateException("model provider error: " + root.path("error").path("message").asString(root.path("error").toString()));
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").isString() ? choice.path("finish_reason").asString() : null;
        boolean refused = (message.path("refusal").isString() && !message.path("refusal").asString().isBlank())
                || "content_filter".equals(finish);
        String text = message.path("content").isString() ? message.path("content").asString() : "";
        text = THINKING.matcher(text).replaceAll("").strip();
        JsonNode u = root.path("usage");
        long prompt = u.path("prompt_tokens").asLong(0);
        long cached = u.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        Usage usage = new Usage(Math.max(0, prompt - cached), u.path("completion_tokens").asLong(0), 0, cached);
        Double cost = u.path("cost").isNumber() ? u.path("cost").asDouble() : null;
        String model = root.path("model").isString() ? root.path("model").asString() : requested;
        return new ModelReply(text, model, finish, refused, usage, latency, cost);
    }
}
