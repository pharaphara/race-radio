package io.github.pharaphara.raceradio.bot.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Calls Claude through the Claude Code command line, authenticated with a Claude subscription
 * rather than an API key: {@code claude setup-token} once, then {@code CLAUDE_CODE_OAUTH_TOKEN} in
 * the service's environment.
 *
 * <p>Measured on a real call, four things matter:
 * <ul>
 *   <li>{@code --bare} must <em>not</em> be used: it only accepts an API key and never reads the
 *       subscription's credentials.</li>
 *   <li>Without {@code --disable-slash-commands}, {@code --setting-sources ""} and
 *       {@code --strict-mcp-config}, the command line adds its own skills, settings and MCP tool
 *       definitions to every request: a one-word answer wrote 30,711 tokens to the cache. With them,
 *       573. They are always passed, and a test checks it.</li>
 *   <li>The prompt goes in through standard input, never as an argument: arguments are visible to
 *       every user of the machine and limited in size, and the prompt holds health data.</li>
 *   <li>The command reports usage, cache included, and a cost at list price; both are recorded,
 *       although a subscription is not billed per call.</li>
 * </ul>
 * It runs in an empty working directory of its own, so no project instructions are picked up.
 */
public final class ClaudeCodeTransport implements ModelTransport {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClaudeCodeTransport.class);

    static final List<String> ISOLATION = List.of("--tools", "", "--no-session-persistence",
            "--disable-slash-commands", "--setting-sources", "", "--strict-mcp-config");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String command;
    private final Path workDir;
    private final Duration timeout;

    public ClaudeCodeTransport(String command, Path workDir, Duration timeout) {
        this.command = command;
        this.workDir = workDir;
        this.timeout = timeout;
    }

    @Override
    public String billing() {
        return "subscription";
    }

    @Override
    public ModelReply send(ModelRequest r, SystemPrompt system) {
        return run(arguments(r, promptFile(system), false), workDir, r.userContent());
    }

    /**
     * The image goes alone into a fresh directory, which becomes the working directory, and the
     * only tool opened is Read. Measured: Claude Code lets the model read anything in its working
     * directory, so isolation comes from the directory holding nothing but the photo; reads
     * elsewhere are refused and reported, and a refusal is logged, since text on a photo can try to
     * instruct the model. The directory is deleted afterwards.
     */
    @Override
    public ModelReply sendWithImage(ModelRequest r, SystemPrompt system, byte[] image, String mediaType) {
        Path dir = null;
        try {
            Path prompt = promptFile(system);
            dir = Files.createTempDirectory(workDir, "photo-");
            String name = "photo" + ("image/png".equals(mediaType) ? ".png" : ".jpg");
            Files.write(dir.resolve(name), image);
            restrict(dir.resolve(name));
            String content = r.userContent() + "\n\nThe photo is the file " + name + " in the current directory; read it.";
            return run(arguments(r, prompt, true), dir, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(dir);
        }
    }

    private Path promptFile(SystemPrompt system) {
        try {
            Files.createDirectories(workDir);
            Path promptFile = workDir.resolve("system-" + system.version() + ".txt");
            if (!Files.exists(promptFile)) {
                Files.writeString(promptFile, system.text(), StandardCharsets.UTF_8);
                restrict(promptFile);
            }
            return promptFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ModelReply run(List<String> cmd, Path cwd, String stdinContent) {
        try {
            // Both streams go to private files, so that a large stderr cannot block the process and
            // the timeout below really bounds the wait. The files hold the reply and are deleted.
            Path outFile = Files.createTempFile(workDir, "out-", ".json");
            Path errFile = Files.createTempFile(workDir, "err-", ".txt");
            restrict(outFile);
            restrict(errFile);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile())
                        .redirectOutput(outFile.toFile()).redirectError(errFile.toFile());
                long started = System.nanoTime();
                Process p = pb.start();
                try (var stdin = p.getOutputStream()) {
                    stdin.write(stdinContent.getBytes(StandardCharsets.UTF_8));
                }
                if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("claude did not answer within " + timeout.toSeconds() + " s");
                }
                long latency = (System.nanoTime() - started) / 1_000_000;
                String out = Files.readString(outFile, StandardCharsets.UTF_8);
                if (p.exitValue() != 0 && out.isBlank()) {
                    throw new IllegalStateException("claude exited with " + p.exitValue() + ": "
                            + firstLine(Files.readString(errFile, StandardCharsets.UTF_8)));
                }
                return parse(out, latency);
            } finally {
                Files.deleteIfExists(outFile);
                Files.deleteIfExists(errFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for claude", e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // best effort: the directory only ever holds the photo
        }
    }

    List<String> arguments(ModelRequest r, Path promptFile, boolean readPhoto) {
        List<String> cmd = new ArrayList<>(List.of(command, "-p", "--output-format", "json",
                "--model", r.model(), "--effort", r.effort(), "--system-prompt-file", promptFile.toString()));
        cmd.addAll(ISOLATION);
        if (readPhoto) {
            int tools = cmd.indexOf("--tools");
            cmd.set(tools + 1, "Read");
        }
        return cmd;
    }

    /** Reads the command's JSON result. Package-visible for tests. */
    static ModelReply parse(String json, long latencyMs) {
        JsonNode d = JSON.readTree(json);
        if (d.path("is_error").asBoolean(false)) {
            // With an error the result is the command line's own message ("requires usage credits", a
            // usage limit reached), never an answer: sending it to the athlete as a review would be wrong.
            throw new IllegalStateException("claude reported an error: " + d.path("subtype").asString("unknown")
                    + (d.path("result").isString() ? ": " + firstLine(d.path("result").asString()) : ""));
        }
        if (d.path("modelUsage").isObject() && d.path("modelUsage").isEmpty() && d.path("usage").path("output_tokens").asLong(0) == 0) {
            throw new IllegalStateException("claude answered without any model: " + firstLine(d.path("result").asString("")));
        }
        JsonNode u = d.path("usage");
        Usage usage = new Usage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("cache_creation_input_tokens").asLong(0), u.path("cache_read_input_tokens").asLong(0));
        String answering = null;
        Iterator<Map.Entry<String, JsonNode>> models = d.path("modelUsage").properties().iterator();
        if (models.hasNext()) {
            answering = models.next().getKey();
        }
        if (d.path("permission_denials").isArray() && !d.path("permission_denials").isEmpty()) {
            LOG.warn("the model tried to use a tool it was refused, possibly instructed by the input: {}", d.path("permission_denials"));
        }
        String stop = d.path("stop_reason").asString(null);
        boolean refused = "refusal".equals(stop);
        Double cost = d.path("total_cost_usd").isNumber() ? d.path("total_cost_usd").asDouble() : null;
        long latency = d.path("duration_ms").isNumber() ? d.path("duration_ms").asLong() : latencyMs;
        return new ModelReply(d.path("result").asString("").strip(), answering, stop, refused, usage, latency, cost);
    }

    private static String firstLine(String s) {
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX file system.
        }
    }
}
