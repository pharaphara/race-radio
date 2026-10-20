package io.github.pharaphara.raceradio.bot.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a stand-in for the claude command that records its arguments and its input. */
class ClaudeCodeTransportTest {

    @TempDir
    Path dir;

    private static final String REPLY = """
            {"type":"result","subtype":"success","is_error":false,"result":"🟢 Entraînement prévu\\nTout va bien.",
             "stop_reason":"end_turn","duration_ms":1894,"total_cost_usd":0.0023,
             "usage":{"input_tokens":2,"cache_creation_input_tokens":573,"cache_read_input_tokens":40,"output_tokens":4},
             "modelUsage":{"claude-sonnet-5-5":{"inputTokens":2,"costUSD":0.0023}}}""";

    private Path fakeClaude(String stdout, int exit, int sleepSeconds) throws IOException {
        Path script = dir.resolve("claude");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > \"" + dir.resolve("args.txt") + "\"\n"
                + "cat > \"" + dir.resolve("stdin.txt") + "\"\n"
                + (sleepSeconds > 0 ? "sleep " + sleepSeconds + "\n" : "")
                + "cat <<'JSON'\n" + stdout + "\nJSON\n"
                + "exit " + exit + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private ModelRequest request(String content) {
        return new ModelRequest(Purpose.MORNING, "claude-sonnet-5-5", "low", 2000, true, "v1", content);
    }

    @Test
    void passesTheIsolationFlagsAndNeverBare() throws IOException {
        var t = new ClaudeCodeTransport(fakeClaude(REPLY, 0, 0).toString(), dir.resolve("work"), Duration.ofSeconds(20));
        t.send(request("bonjour"), SystemPrompt.of("rules"));
        List<String> args = Files.readAllLines(dir.resolve("args.txt"));
        assertThat(args).contains("-p", "--disable-slash-commands", "--strict-mcp-config", "--no-session-persistence",
                "--setting-sources", "--tools", "--system-prompt-file", "--effort", "low", "--model", "claude-sonnet-5-5");
        assertThat(args).doesNotContain("--bare");
    }

    @Test
    void sendsThePromptThroughStandardInputNeverAsAnArgument() throws IOException {
        var t = new ClaudeCodeTransport(fakeClaude(REPLY, 0, 0).toString(), dir.resolve("work"), Duration.ofSeconds(20));
        t.send(request("VFC 30 ms ce matin, données de santé"), SystemPrompt.of("rules"));
        assertThat(Files.readString(dir.resolve("stdin.txt"))).isEqualTo("VFC 30 ms ce matin, données de santé");
        assertThat(Files.readString(dir.resolve("args.txt"))).doesNotContain("VFC");
    }

    @Test
    void readsUsageCacheIncludedAndTheReportedCost() throws IOException {
        var t = new ClaudeCodeTransport(fakeClaude(REPLY, 0, 0).toString(), dir.resolve("work"), Duration.ofSeconds(20));
        ModelReply r = t.send(request("x"), SystemPrompt.of("rules"));
        assertThat(r.text()).startsWith("🟢");
        assertThat(r.usage()).isEqualTo(new Usage(2, 4, 573, 40));
        assertThat(r.reportedCostUsd()).isEqualTo(0.0023);
        assertThat(r.answeringModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(r.refused()).isFalse();
        assertThat(t.billing()).isEqualTo("subscription");
    }

    @Test
    void theSystemPromptFileAndTheTemporaryFilesArePrivate() throws IOException {
        Path work = dir.resolve("work");
        new ClaudeCodeTransport(fakeClaude(REPLY, 0, 0).toString(), work, Duration.ofSeconds(20))
                .send(request("x"), SystemPrompt.of("rules"));
        try (var files = Files.list(work)) {
            List<Path> left = files.toList();
            assertThat(left).hasSize(1).allMatch(p -> p.getFileName().toString().startsWith("system-"));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(left.getFirst()))).isEqualTo("rw-------");
        }
    }

    @Test
    void aRefusalIsReportedAsSuch() throws IOException {
        String refused = REPLY.replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"refusal\"");
        ModelReply r = new ClaudeCodeTransport(fakeClaude(refused, 0, 0).toString(), dir.resolve("work"), Duration.ofSeconds(20))
                .send(request("x"), SystemPrompt.of("rules"));
        assertThat(r.refused()).isTrue();
    }

    @Test
    void aHangingCommandIsStoppedAtTheTimeout() throws IOException {
        var t = new ClaudeCodeTransport(fakeClaude(REPLY, 0, 30).toString(), dir.resolve("work"), Duration.ofSeconds(1));
        assertThatThrownBy(() -> t.send(request("x"), SystemPrompt.of("rules"))).hasMessageContaining("did not answer");
    }

    @Test
    void theCommandLinesOwnMessageIsNeverTakenForAnAnswer() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ClaudeCodeTransport.parse("""
                {"type":"result","subtype":"success","is_error":true,"result":"Fable 5.1 requires usage credits.","usage":{"output_tokens":0}}""", 5))
                .hasMessageContaining("requires usage credits");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ClaudeCodeTransport.parse("""
                {"type":"result","subtype":"success","is_error":false,"result":"Usage limit reached.","usage":{"output_tokens":0},"modelUsage":{}}""", 5))
                .hasMessageContaining("without any model");
    }

    @Test
    void aFailingCommandWithoutOutputIsAnError() throws IOException {
        var t = new ClaudeCodeTransport(fakeClaude("", 1, 0).toString(), dir.resolve("work"), Duration.ofSeconds(20));
        assertThatThrownBy(() -> t.send(request("x"), SystemPrompt.of("rules"))).isInstanceOf(RuntimeException.class);
    }
}
