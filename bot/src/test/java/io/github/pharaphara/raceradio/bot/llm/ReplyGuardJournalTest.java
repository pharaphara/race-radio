package io.github.pharaphara.raceradio.bot.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The guard replayed over a real journal, {@code mvn test -Djournal=<data>/llm}: answers written by
 * Claude keep the rules, so every one it flags is a false alarm to look at. Reads private data, so it
 * only runs when pointed at it, and prints what it found rather than asserting a rate.
 */
@EnabledIfSystemProperty(named = "journal", matches = ".+")
class ReplyGuardJournalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void falseAlarmsOnAnswersFromTheJournal() throws IOException {
        Path dir = Path.of(System.getProperty("journal"));
        Map<String, int[]> counts = new TreeMap<>();
        List<Path> days;
        try (var files = Files.list(dir)) {
            days = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
        for (Path day : days) {
            for (String line : Files.readAllLines(day)) {
                JsonNode n = JSON.readTree(line);
                JsonNode req = n.path("request");
                String answer = n.path("reply").path("text").asString("");
                if (answer.isBlank()) {
                    continue;
                }
                Path system = dir.resolve("system-" + req.path("promptVersion").asString() + ".txt");
                if (!Files.exists(system)) {
                    continue;
                }
                ModelRequest r = new ModelRequest(Purpose.valueOf(req.path("purpose").asString()), req.path("model").asString(),
                        req.path("effort").asString(), req.path("maxTokens").asLong(), false, req.path("promptVersion").asString(),
                        req.path("userContent").asString());
                List<String> problems = ReplyGuard.problems(r, SystemPrompt.of(Files.readString(system)), answer);
                int[] c = counts.computeIfAbsent(r.purpose().name(), k -> new int[2]);
                c[0]++;
                if (!problems.isEmpty()) {
                    c[1]++;
                    System.out.println("FLAGGED " + r.purpose() + " " + n.path("ref").asString() + ": " + problems);
                }
            }
        }
        counts.forEach((purpose, c) -> System.out.println("GUARD " + purpose + ": " + c[1] + " flagged of " + c[0]));
    }
}
