package io.github.pharaphara.raceradio.bot.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleTransportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SystemPrompt SYSTEM = SystemPrompt.of("You are a coach.");
    private static final ModelRequest REQUEST = new ModelRequest(Purpose.CHAT, "vendor/model:free", "low", 2000, false, "v1", "Bonjour");

    private static JsonNode body(String baseUrl) {
        return new OpenAiCompatibleTransport(baseUrl, "k", Duration.ofSeconds(5))
                .body(REQUEST, SYSTEM, JSON.getNodeFactory().textNode("Bonjour"));
    }

    @Test
    void eachServiceGetsTheFieldsItUnderstands() {
        JsonNode openRouter = body("");
        assertThat(openRouter.path("max_tokens").asLong()).isEqualTo(2000);
        assertThat(openRouter.path("reasoning").path("effort").asString()).isEqualTo("low");
        assertThat(openRouter.path("usage").path("include").asBoolean()).isTrue();
        assertThat(openRouter.path("messages").path(0).path("content").asString()).isEqualTo("You are a coach.");

        JsonNode openAi = body("https://api.openai.com/v1/");
        assertThat(openAi.has("max_tokens")).isFalse();
        assertThat(openAi.path("max_completion_tokens").asLong()).isEqualTo(2000);
        assertThat(openAi.path("reasoning_effort").asString()).isEqualTo("low");

        JsonNode local = body("http://ollama:11434/v1");
        assertThat(local.has("reasoning")).isFalse();
        assertThat(local.has("reasoning_effort")).isFalse();
    }

    @Test
    void aReplyGivesItsTextUsageAndReportedCostWithoutTheReasoning() {
        JsonNode root = JSON.readTree("""
                {"model":"vendor/model","choices":[{"finish_reason":"stop","message":{"content":"<think>hmm</think>\\nSalut."}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":1000},"cost":0}}""");
        ModelReply r = OpenAiCompatibleTransport.parse(root, "vendor/model:free", 12);
        assertThat(r.text()).isEqualTo("Salut.");
        assertThat(r.answeringModel()).isEqualTo("vendor/model");
        assertThat(r.usage()).isEqualTo(new Usage(200, 40, 0, 1000));
        assertThat(r.reportedCostUsd()).isZero();
        assertThat(r.refused()).isFalse();
    }

    @Test
    void refusalsAndProviderErrorsAreRecognised() {
        assertThat(OpenAiCompatibleTransport.parse(JSON.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"I can't help with that."}}]}"""), "m", 1).refused()).isTrue();
        assertThat(OpenAiCompatibleTransport.parse(JSON.readTree("""
                {"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}"""), "m", 1).refused()).isTrue();
        assertThatThrownBy(() -> OpenAiCompatibleTransport.parse(JSON.readTree("""
                {"error":{"message":"Rate limit exceeded","code":429}}"""), "m", 1))
                .hasMessageContaining("Rate limit exceeded");
    }

    @Test
    void itPostsToTheChatEndpointWithTheKeyAndTheImage() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seen.add(ex.getRequestHeaders().getFirst("Authorization"));
            seen.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = """
                    {"model":"m","choices":[{"finish_reason":"stop","message":{"content":"3 x 10 min"}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/down/chat/completions", ex -> {
            byte[] out = "{\"error\":{\"message\":\"overloaded\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(503, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var t = new OpenAiCompatibleTransport(base + "/v1", "secret", Duration.ofSeconds(5));
            ModelReply r = t.sendWithImage(REQUEST, SYSTEM, new byte[]{1, 2, 3}, "image/png");
            assertThat(r.text()).isEqualTo("3 x 10 min");
            assertThat(seen.get(0)).isEqualTo("Bearer secret");
            assertThat(seen.get(1)).contains("\"type\":\"image_url\"").contains("data:image/png;base64,AQID");
            assertThatThrownBy(() -> new OpenAiCompatibleTransport(base + "/down", "", Duration.ofSeconds(5)).send(REQUEST, SYSTEM))
                    .hasMessageContaining("HTTP 503");
        } finally {
            server.stop(0);
        }
    }
}
