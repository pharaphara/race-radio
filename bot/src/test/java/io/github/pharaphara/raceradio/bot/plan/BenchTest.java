package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.llm.ClaudeCodeTransport;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.ModelRequest;
import io.github.pharaphara.raceradio.bot.llm.ModelTransport;
import io.github.pharaphara.raceradio.bot.llm.OpenAiCompatibleTransport;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.llm.ReplyGuard;
import io.github.pharaphara.raceradio.bot.llm.SystemPrompt;
import io.github.pharaphara.raceradio.core.plan.Thresholds;
import io.github.pharaphara.raceradio.core.plan.WeekCheck;
import io.github.pharaphara.raceradio.core.workout.Draft;
import io.github.pharaphara.raceradio.core.workout.Workout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The same requests put to several models, scored by the grid agreed before the run.
 *
 * <p>{@code -Dbench=<dir>} holds {@code tasks.jsonl} (one recorded request per line, as the journal
 * keeps them, with a task id and the expected sport and intent for workouts) and the system prompts
 * they name. {@code -Dbench.phase=run} sends each task to each model of {@code -Dbench.models}
 * ({@code or:vendor/model} through OpenRouter, {@code claude:model} through the subscription) and
 * appends to {@code results.jsonl}, skipping pairs already done; {@code -Dbench.phase=judge} has a
 * judge model score every task's answers blind, in a shuffled order, into {@code judgements.jsonl}.
 * Reads private data and spends quota, so it only runs when asked.
 */
@EnabledIfSystemProperty(named = "bench", matches = ".+")
class BenchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MAX_TOKENS = 8_000;
    private static final Pattern DELEGATION = Pattern.compile("^(JOUR|ACTIVITE|COMPARE|TENDANCE|SEANCE|SEMAINE|DEPLACER|MODIFIER)\\b.*", Pattern.DOTALL);

    private final Path dir = Path.of(System.getProperty("bench", "."));

    private record Task(String id, ModelRequest request, SystemPrompt system, String sport, boolean hard) {
    }

    private List<Task> tasks() throws IOException {
        List<Task> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("tasks.jsonl"))) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = JSON.readTree(line);
            JsonNode r = n.path("request");
            ModelRequest req = new ModelRequest(Purpose.valueOf(r.path("purpose").asString()), r.path("model").asString(),
                    r.path("effort").asString(), Math.max(MAX_TOKENS, r.path("maxTokens").asLong()), false,
                    r.path("promptVersion").asString(), r.path("userContent").asString());
            SystemPrompt system = SystemPrompt.of(Files.readString(dir.resolve("system-" + req.promptVersion() + ".txt")));
            out.add(new Task(n.path("task").asString(), req, system, n.path("sport").asString(null), n.path("hard").asBoolean(false)));
        }
        return out;
    }

    private static ModelTransport transport(String model) {
        if (model.startsWith("or:")) {
            return new OpenAiCompatibleTransport("", System.getenv("RACE_RADIO_MODEL_API_KEY"), Duration.ofMinutes(3));
        }
        return new ClaudeCodeTransport(System.getenv().getOrDefault("CLAUDE_COMMAND", "claude"),
                Path.of(System.getProperty("bench")).resolve("claude-work"), Duration.ofMinutes(5));
    }

    private static String name(String model) {
        return model.substring(model.indexOf(':') + 1);
    }

    @Test
    @EnabledIfSystemProperty(named = "bench.phase", matches = "run")
    void run() throws IOException {
        List<Task> tasks = tasks();
        Thresholds t = thresholds();
        Path results = dir.resolve("results.jsonl");
        Set<String> done = new HashSet<>();
        if (Files.exists(results)) {
            for (String line : Files.readAllLines(results)) {
                JsonNode n = JSON.readTree(line);
                done.add(n.path("task").asString() + "|" + n.path("model").asString());
            }
        }
        for (String model : System.getProperty("bench.models").split(",")) {
            ModelTransport transport = transport(model.strip());
            for (Task task : tasks) {
                if (done.contains(task.id() + "|" + model.strip())) {
                    continue;
                }
                ModelRequest r = new ModelRequest(task.request().purpose(), name(model.strip()), task.request().effort(),
                        task.request().maxTokens(), false, task.request().promptVersion(), task.request().userContent());
                ObjectNode row = JSON.createObjectNode();
                row.put("task", task.id()).put("model", model.strip()).put("purpose", r.purpose().name());
                long started = System.nanoTime();
                try {
                    ModelReply reply = transport.send(r, task.system());
                    row.put("text", reply.text()).put("latencyMs", reply.latencyMs() > 0 ? reply.latencyMs() : (System.nanoTime() - started) / 1_000_000)
                            .put("inputTokens", reply.usage().input() + reply.usage().cacheRead() + reply.usage().cacheWrite())
                            .put("outputTokens", reply.usage().output()).put("refused", reply.refused());
                    row.set("checks", checks(task, reply.text(), t));
                } catch (RuntimeException e) {
                    row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                            .put("latencyMs", (System.nanoTime() - started) / 1_000_000);
                    ObjectNode c = JSON.createObjectNode();
                    c.put("eliminated", true).putArray("why").add("no answer: " + e.getMessage());
                    row.set("checks", c);
                }
                Files.writeString(results, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.println("BENCH " + task.id() + " " + model + " " + row.path("checks"));
            }
        }
    }

    /** The automatic checks again over the answers already collected, after a change to them: no call. */
    @Test
    @EnabledIfSystemProperty(named = "bench.phase", matches = "recheck")
    void recheck() throws IOException {
        Map<String, Task> tasks = new LinkedHashMap<>();
        tasks().forEach(task -> tasks.put(task.id(), task));
        Thresholds t = thresholds();
        Path results = dir.resolve("results.jsonl");
        StringBuilder b = new StringBuilder();
        for (String line : Files.readAllLines(results)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            if (row.path("text").isString()) {
                row.set("checks", checks(tasks.get(row.path("task").asString()), row.path("text").asString(), t));
            }
            b.append(JSON.writeValueAsString(row)).append('\n');
        }
        Files.writeString(results, b.toString(), StandardCharsets.UTF_8);
        System.out.println("BENCH rechecked");
    }

    private static Thresholds thresholds() {
        String[] v = System.getProperty("bench.thresholds", "250,300,120").split(",");
        return new Thresholds(Integer.valueOf(v[0].strip()), Integer.valueOf(v[1].strip()), Integer.valueOf(v[2].strip()));
    }

    /** The automatic part of the grid: eliminating faults, then form, measured the same for all. */
    static ObjectNode checks(Task task, String text, Thresholds t) {
        ObjectNode c = JSON.createObjectNode();
        List<String> why = new ArrayList<>();
        List<String> form = new ArrayList<>();
        if (text == null || text.isBlank()) {
            why.add("empty answer");
        } else {
            why.addAll(ReplyGuard.problems(task.request(), task.system(), text));
            switch (task.request().purpose()) {
                case SESSION -> session(task, text, t, why, c);
                case REVIEW -> {
                    int words = text.split("\\s+").length;
                    c.put("words", words);
                    if (words < 150 || words > 250) {
                        form.add(words + " words, 150 to 250 asked");
                    }
                    if (!text.toLowerCase().contains("et ensuite")) {
                        form.add("no « Et ensuite »");
                    }
                    if (!text.contains("**") && !text.contains("<b>")) {
                        form.add("no bold section titles");
                    }
                }
                case CHAT -> {
                    if (DELEGATION.matcher(text.strip()).matches()) {
                        c.put("delegated", text.strip().lines().findFirst().orElse(""));
                    } else {
                        int cut = text.toUpperCase().lastIndexOf("SUGGESTIONS:");
                        if (cut < 0) {
                            form.add("no SUGGESTIONS block");
                        } else {
                            List<String> s = text.substring(cut).lines().map(String::strip).filter(l -> l.startsWith("-")).toList();
                            if (s.size() != 3) {
                                form.add(s.size() + " suggestions, 3 asked");
                            }
                            if (s.stream().anyMatch(l -> l.length() - 1 > 41)) {
                                form.add("a suggestion over 40 characters");
                            }
                        }
                    }
                }
                default -> { }
            }
        }
        c.put("eliminated", !why.isEmpty());
        why.forEach(c.withArray("why")::add);
        form.forEach(c.withArray("form")::add);
        return c;
    }

    private static void session(Task task, String text, Thresholds t, List<String> why, ObjectNode c) {
        try {
            JsonNode root = JSON.readTree(SessionCreator.json(text));
            JsonNode node = root.has("workout") ? root.path("workout") : root;
            Workout w = SessionCreator.buildable(JSON.treeToValue(node, Draft.class).build(t));
            c.put("preview", SessionCreator.preview(LocalDate.now().plusDays(1), w, t,
                    root.path("why").asString(""), root.path("note").asString(""), ""));
            if (task.sport() != null && !Set.of(task.sport().split("\\|")).contains(w.sport())) {
                why.add("sport " + w.sport() + ", asked " + task.sport());
            }
            if (task.hard() != WeekCheck.hard(w, t)) {
                why.add(task.hard() ? "an easy workout for a hard request" : "a hard workout for an easy request");
            }
        } catch (RuntimeException e) {
            why.add("workout not publishable: " + e.getMessage());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "bench.phase", matches = "judge")
    void judge() throws IOException {
        Map<String, Task> tasks = new LinkedHashMap<>();
        tasks().forEach(task -> tasks.put(task.id(), task));
        Map<String, List<JsonNode>> answers = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dir.resolve("results.jsonl"))) {
            JsonNode n = JSON.readTree(line);
            if (n.path("text").isString() && !n.path("text").asString().isBlank()) {
                answers.computeIfAbsent(n.path("task").asString(), k -> new ArrayList<>()).add(n);
            }
        }
        Path out = dir.resolve("judgements.jsonl");
        Set<String> judged = new HashSet<>();
        if (Files.exists(out)) {
            for (String line : Files.readAllLines(out)) {
                judged.add(JSON.readTree(line).path("task").asString());
            }
        }
        String judgeModel = System.getProperty("bench.judge", "claude:claude-opus-5-5");
        ModelTransport transport = transport(judgeModel);
        SystemPrompt judgeSystem = SystemPrompt.of("""
                You grade the answers of an automated triathlon coach, blind. You never know which model wrote which
                answer, and length or polish alone earns nothing. Answer with JSON only.""");
        Random random = new Random(42);
        for (Map.Entry<String, List<JsonNode>> e : answers.entrySet()) {
            if (judged.contains(e.getKey())) {
                continue;
            }
            Task task = tasks.get(e.getKey());
            List<JsonNode> list = new ArrayList<>(e.getValue());
            Collections.shuffle(list, random);
            boolean session = task.request().purpose() == Purpose.SESSION;
            StringBuilder b = new StringBuilder();
            b.append("""
                    The coach follows these standing rules and knowledge base:
                    <rules>
                    %s
                    </rules>

                    It was given this task, with the facts computed by the program:
                    <task>
                    %s
                    </task>

                    Answers, labelled in a random order:
                    """.formatted(task.system().text(), task.request().userContent()));
            Map<String, String> labels = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                String label = String.valueOf((char) ('A' + i));
                labels.put(label, list.get(i).path("model").asString());
                String shown = session && list.get(i).path("checks").has("preview")
                        ? list.get(i).path("checks").path("preview").asString() : list.get(i).path("text").asString();
                b.append("\n<answer label=\"").append(label).append("\">\n").append(shown).append("\n</answer>\n");
            }
            b.append(session ? """

                    Score each answer from 1 (poor) to 5 (excellent) on:
                    - fidelity: calibration, intensities and durations right for the request and the athlete's context
                    - advice: structure, warm-up, recovery ratios, a sound execution note
                    - usefulness: ready to do, clear cues
                    - french: natural, concise French
                    - form: the requested shape
                    """ : """

                    Score each answer from 1 (poor) to 5 (excellent) on:
                    - fidelity: every statement supported by the facts, read correctly (a VO2 session is not "tempo")
                    - advice: physiologically sound, fitted to the context (fatigue, plan, next session)
                    - usefulness: says what to do next, concretely
                    - french: natural, concise, a coach's voice, no compliment without a fact
                    - form: the shape the task asks for
                    A reply that hands the question to the program (a single line such as TENDANCE ...) is a valid
                    choice when the task allows it: judge whether it was the right one.
                    """);
            b.append("""
                    Answer exactly: {"<label>": {"fidelity": n, "advice": n, "usefulness": n, "french": n, "form": n,
                    "comment": "one short sentence in French"}, ...} for every label.""");
            ModelRequest r = new ModelRequest(Purpose.CHAT, name(judgeModel), "high", 16_000, false, judgeSystem.version(), b.toString());
            ModelReply reply = transport.send(r, judgeSystem);
            JsonNode scores = JSON.readTree(SessionCreator.json(reply.text()));
            ObjectNode row = JSON.createObjectNode();
            row.put("task", e.getKey()).put("judge", judgeModel);
            ObjectNode byModel = row.putObject("scores");
            labels.forEach((label, model) -> byModel.set(model, scores.path(label)));
            Files.writeString(out, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("JUDGED " + e.getKey());
        }
    }
}
