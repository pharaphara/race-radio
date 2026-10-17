package io.github.pharaphara.raceradio.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repository is public from its first commit, so what must never be published is checked by a
 * test rather than by vigilance.
 *
 * <p>The files examined are exactly those git would publish: tracked files plus untracked files
 * that are not ignored. A secret sitting in an ignored {@code .env} is fine; the same secret pasted
 * into a source file fails the build.
 */
class RepositoryHygieneTest {

    /** Files that legitimately contain the patterns this test hunts for. */
    private static final List<String> EXEMPT = List.of(
            "LICENSE",
            "core/src/test/java/io/github/pharaphara/raceradio/core/RepositoryHygieneTest.java");

    /** Trademarked abbreviations are allowed only where the knowledge base describes the science. */
    private static final String KNOWLEDGE = "knowledge/";

    private static final Map<String, Pattern> SECRETS = Map.of(
            "Intervals.icu athlete id", Pattern.compile("\\bi\\d{6,8}\\b"),
            "Telegram bot token", Pattern.compile("\\b\\d{8,10}:[A-Za-z0-9_-]{35}\\b"),
            "Anthropic API key", Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            "Strava client secret", Pattern.compile("(?i)strava[_-]?client[_-]?secret\\s*[=:]\\s*[0-9a-f]{40}"));

    /**
     * Case-sensitive whole words, so that Java's {@code if} or the word "atlas" never trip it.
     * The public vocabulary is: weighted average power, intensity, load, fitness, fatigue, form.
     */
    private static final Pattern TRADEMARKS = Pattern.compile(
            "\\b(NP|TSS|IF|CTL|ATL|TSB|rTSS|hrTSS|sTSS)\\b|Normalized Power|Training Stress (Score|Balance)|Intensity Factor");

    private static Path root;
    private static List<Path> publishable;

    @BeforeAll
    static void listPublishableFiles() throws Exception {
        root = Path.of("").toAbsolutePath().getParent();
        Process git = new ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard")
                .directory(root.toFile()).redirectErrorStream(true).start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git ls-files must run from the repository root").isZero();
        publishable = out.lines().filter(l -> !l.isBlank()).map(root::resolve).filter(Files::isRegularFile).toList();
        assertThat(publishable).as("the scan must actually see files").isNotEmpty();
    }

    @Test
    void noSecretOrPersonalIdentifierIsPublished() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : textFiles()) {
            String text = Files.readString(file);
            SECRETS.forEach((what, pattern) -> {
                if (pattern.matcher(text).find()) {
                    findings.add(relative(file) + ": " + what);
                }
            });
        }
        assertThat(findings).as("secrets or personal identifiers in publishable files").isEmpty();
    }

    @Test
    void noValueFromTheLocalEnvFileLeaksIntoPublishedFiles() throws IOException {
        Path env = root.resolve(".env");
        if (!Files.exists(env)) {
            return;
        }
        List<String> values = Files.readAllLines(env).stream()
                .map(String::strip)
                .filter(l -> !l.startsWith("#") && l.contains("="))
                .map(l -> l.substring(l.indexOf('=') + 1).strip().replace("\"", ""))
                .filter(v -> v.length() >= 8)
                .toList();
        List<String> findings = new ArrayList<>();
        for (Path file : textFiles()) {
            String text = Files.readString(file);
            for (String v : values) {
                if (text.contains(v)) {
                    findings.add(relative(file));
                }
            }
        }
        assertThat(findings).as("a value from .env was copied into a publishable file").isEmpty();
    }

    @Test
    void trademarkedAbbreviationsStayInTheKnowledgeBase() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : textFiles()) {
            // SQL is neither message nor interface, and its own keyword IF NOT EXISTS would match.
            if (relative(file).startsWith(KNOWLEDGE) || relative(file).endsWith(".sql")) {
                continue;
            }
            var m = TRADEMARKS.matcher(Files.readString(file));
            while (m.find()) {
                findings.add(relative(file) + ": " + m.group());
            }
        }
        assertThat(findings).as("use weighted average power, intensity, load, fitness, fatigue, form").isEmpty();
    }

    @Test
    void noSourceFileIsHiddenFromGitByAnIgnoreRule() throws Exception {
        // An unanchored "llm/" in .gitignore once hid a whole source package: the repository then
        // did not compile from a clean clone, and every check above simply never saw those files.
        Process git = new ProcessBuilder("git", "ls-files", "--others", "--ignored", "--exclude-standard", "--directory")
                .directory(root.toFile()).redirectErrorStream(true).start();
        List<String> ignored = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(l -> l.contains("/src/") || l.startsWith("knowledge/")).toList();
        assertThat(git.waitFor()).isZero();
        assertThat(ignored).as("source files ignored by git").isEmpty();
    }

    private static List<Path> textFiles() {
        return publishable.stream()
                .filter(p -> !EXEMPT.contains(relative(p)))
                .filter(RepositoryHygieneTest::isText)
                .collect(Collectors.toList());
    }

    private static boolean isText(Path p) {
        String name = p.getFileName().toString();
        return !(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jar") || name.endsWith(".ttf"));
    }

    private static String relative(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
