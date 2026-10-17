package io.github.pharaphara.raceradio.bot.llm;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * What was sent to the model, exactly, and what came back. One JSON line per call, one file per
 * day, readable only by the service's user. The system prompt is stored once per version beside
 * the lines rather than repeated in each, so that a line plus its prompt file is enough to rebuild
 * the request.
 *
 * <p>The journal holds the athlete's health data. It lives outside the repository, is purged after
 * a configurable number of days, and can be wiped entirely.
 */
public final class Journal {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Path dir;

    public Journal(Path dir) {
        this.dir = dir;
    }

    /** Appends one call and returns its reference. */
    public String write(Instant at, ModelRequest request, SystemPrompt system, ModelReply reply, String error) {
        String ref = UUID.randomUUID().toString();
        try {
            ensureDir();
            Path prompt = dir.resolve("system-" + system.version() + ".txt");
            if (!Files.exists(prompt)) {
                writePrivate(prompt, system.text(), false);
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ref", ref);
            line.put("at", at.toString());
            line.put("request", request);
            line.put("reply", reply);
            line.put("error", error);
            writePrivate(dir.resolve(LocalDate.ofInstant(at, ZoneOffset.UTC) + ".jsonl"), JSON.writeValueAsString(line) + "\n", true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ref;
    }

    /** The most recent line, for /dernierenvoi. */
    public Optional<Map<String, Object>> last() {
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            Optional<Path> newest = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).max(Path::compareTo);
            if (newest.isEmpty()) {
                return Optional.empty();
            }
            var lines = Files.readAllLines(newest.get());
            if (lines.isEmpty()) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.readValue(lines.getLast(), Map.class);
            return Optional.of(m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes day files older than {@code days}. Returns how many were removed. */
    public int purgeOlderThan(int days, LocalDate today) {
        LocalDate limit = today.minusDays(days);
        int removed = 0;
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".jsonl") && LocalDate.parse(name.substring(0, 10)).isBefore(limit)) {
                    Files.delete(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return removed;
    }

    private void ensureDir() throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            setPosix(dir, "rwx------");
        }
    }

    private static void writePrivate(Path file, String content, boolean append) throws IOException {
        boolean created = !Files.exists(file);
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        if (created) {
            setPosix(file, "rw-------");
        }
    }

    private static void setPosix(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX file system: nothing to restrict.
        }
    }
}
