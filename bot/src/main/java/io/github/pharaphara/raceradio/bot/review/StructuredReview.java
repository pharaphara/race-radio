package io.github.pharaphara.raceradio.bot.review;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The review as data rather than prose: the model fills a fixed shape and names every figure by
 * where it sits in the facts, {@code {power.weightedAveragePower}}; the program checks the shape,
 * puts the figures in, in bold, and lays the message out. A figure the model computed or made up
 * cannot get through: a reference that leads nowhere, or a number written by hand, refuses the
 * answer. Meant for models that do not keep the format or the figures on their own.
 */
public final class StructuredReview {

    /** Present in the prompt of this shape, so a replayed request can be recognised. */
    public static final String MARKER = "Answer with JSON in this shape";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern REFERENCE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*(?:\\[\\d+\\])?(?:\\.[A-Za-z][A-Za-z0-9_]*(?:\\[\\d+\\])?)*)\\}");
    /** Numbers a writer may type: counts, days, efforts out of ten. */
    private static final Pattern HAND_NUMBER = Pattern.compile("(?<![\\p{L}\\d.,])\\d+(?:[.,]\\d+)?(?![\\d])");
    private static final int SMALL = 31;

    private StructuredReview() {
    }

    /** The shape asked of the model, in place of the prose layout. */
    static String shape() {
        return MARKER + """
                , and nothing else:
                {"verdict": "one sentence of verdict",
                 "sections": [{"title": "Exécution", "points": ["...", "..."]}],
                 "next": "the next session if there is one, and one concrete recommendation"}
                - verdict: how the session went against what was planned, or, without a plan, what it was.
                  No compliment that a fact does not support.
                - sections: two to four, only those with something to say, each with two or three short
                  points, titles among: Exécution (planned against done, deviations named), Intensité et
                  charge (weighted power, intensity, load, variability explained by the climbing, heat when
                  temperature is high), Effort de pointe (the best five or twenty minutes and the heart rate
                  they cost), Technique (cadence or stride when it stands out), Forme du jour (that morning
                  against the week, the effort rating and feel entered on the watch), Segments (the notable
                  passages given: a record first).
                - Every figure is a reference to the facts in braces, never a number you type: {power.load},
                  {work[1].effort}, {context.morning.hrv}. The program puts the value in, so write the unit
                  only when the value lacks it ("{context.temperature} °C"). A reference to a field that does
                  not exist refuses the whole answer. Small counts and days (up to 31) may be written as words
                  or digits.
                - 120 to 220 words of French once the values are in, all fields together.
                """;
    }

    /**
     * The message for Telegram, from the model's answer and the facts it was given.
     *
     * @throws IllegalArgumentException when the shape is wrong, a reference leads nowhere or a figure
     *                                  was typed by hand; the message names which
     */
    public static String render(String factsJson, String answer) {
        JsonNode facts = JSON.readTree(factsJson);
        int from = answer.indexOf('{');
        int to = answer.lastIndexOf('}');
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("the answer is not JSON");
        }
        JsonNode root;
        try {
            root = JSON.readTree(answer.substring(from, to + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the answer is not valid JSON");
        }
        List<String> problems = new ArrayList<>();
        String verdict = fill(text(root, "verdict", problems), facts, problems);
        StringBuilder b = new StringBuilder(verdict);
        JsonNode sections = root.path("sections");
        if (!sections.isArray() || sections.isEmpty() || sections.size() > 4) {
            problems.add("one to four sections expected, got " + (sections.isArray() ? sections.size() : "none"));
        } else {
            for (JsonNode s : sections) {
                b.append("\n\n**").append(text(s, "title", problems)).append("**");
                JsonNode points = s.path("points");
                if (!points.isArray() || points.isEmpty() || points.size() > 3) {
                    problems.add("one to three points per section");
                    continue;
                }
                for (JsonNode p : points) {
                    b.append("\n• ").append(fill(p.isString() ? p.asString() : "", facts, problems));
                }
            }
        }
        b.append("\n\n**Et ensuite**\n").append(fill(text(root, "next", problems), facts, problems));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return b.toString();
    }

    private static String text(JsonNode n, String field, List<String> problems) {
        if (!n.path(field).isString() || n.path(field).asString().isBlank()) {
            problems.add("missing " + field);
            return "";
        }
        return n.path(field).asString().strip();
    }

    /** References replaced by their value in bold; any other number above the small ones is a fault. */
    public static String fill(String text, JsonNode facts, List<String> problems) {
        return fill(text, facts, problems, java.util.Set.of());
    }

    /**
     * @param copyable  numbers that may be typed because the facts hold them inside longer text, where
     *                  no reference can single them out; empty to allow none
     */
    public static String fill(String text, JsonNode facts, List<String> problems, java.util.Set<Double> copyable) {
        Matcher hand = HAND_NUMBER.matcher(REFERENCE.matcher(text).replaceAll(" "));
        while (hand.find()) {
            double v = Double.parseDouble(hand.group().replace(',', '.'));
            if ((v > SMALL || v != Math.rint(v)) && !io.github.pharaphara.raceradio.bot.llm.ReplyGuard.known(v, copyable)) {
                problems.add("figure typed by hand: " + hand.group());
            }
        }
        Matcher m = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            JsonNode value = resolve(facts, m.group(1));
            if (value == null || value.isMissingNode() || value.isNull() || value.isContainer()) {
                problems.add("no figure at {" + m.group(1) + "}");
                m.appendReplacement(out, "");
                continue;
            }
            m.appendReplacement(out, Matcher.quoteReplacement("**" + format(value) + "**"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static JsonNode resolve(JsonNode facts, String path) {
        JsonNode n = facts;
        for (String part : path.split("\\.")) {
            int bracket = part.indexOf('[');
            String name = bracket < 0 ? part : part.substring(0, bracket);
            n = n.path(name);
            if (bracket >= 0) {
                n = n.path(Integer.parseInt(part.substring(bracket + 1, part.length() - 1)));
            }
            if (n.isMissingNode()) {
                return null;
            }
        }
        return n;
    }

    /** Numbers in French, as precise as the facts give them; text as it is. */
    private static String format(JsonNode v) {
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d == Math.rint(d)) {
                return String.valueOf((long) d);
            }
            return v.asString().replace('.', ',');
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "oui" : "non";
        }
        return v.asString();
    }
}
