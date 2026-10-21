package io.github.pharaphara.raceradio.bot.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a model's prose against what it was given, for models that cannot be trusted to keep the
 * rules on their own, such as a small free one. Two checks, both cheap and local:
 * <ul>
 *   <li><b>No invented number.</b> Every number in the answer must appear in what was sent, the
 *       facts or the standing instructions, as such or rounded. Small whole numbers (up to 31) pass:
 *       days of the month, counts, efforts out of 10.</li>
 *   <li><b>No explanation the knowledge base rejects</b>: toxins, lactate to flush out.</li>
 * </ul>
 * Workouts are JSON checked by the program itself, and a photo's numbers come from the image, so
 * only the explanations are checked there.
 */
public final class ReplyGuard {

    /** Numbers as written in French or English, a space or a narrow space between thousands. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.,])-?\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+(?:[.,]\\d+)?(?![\\d])|(?<![\\d.,])-?\\d+(?:[.,]\\d+)?(?![\\d])");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final double SMALL = 31;
    private static final List<Pattern> NONSENSE = List.of(
            Pattern.compile("(?i)toxine"),
            Pattern.compile("(?i)acide lactique"),
            Pattern.compile("(?i)(élimin|evacu|évacu|purg|nettoy|flush)\\w*\\s+(?:\\S+\\s+){0,3}(?:du |le |les |des )?lactate"),
            Pattern.compile("(?i)lactates?\\s+(?:\\S+\\s+){0,2}(?:à |a )?(?:élimin|évacu|evacu)"));

    private ReplyGuard() {
    }

    /** What is wrong with the answer, empty when nothing is. */
    public static List<String> problems(ModelRequest request, SystemPrompt system, String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return out;
        }
        for (Pattern p : NONSENSE) {
            Matcher m = p.matcher(answer);
            if (m.find()) {
                out.add("explanation without basis: \"" + m.group() + "\"");
            }
        }
        if (request.purpose() == Purpose.MORNING || request.purpose() == Purpose.REVIEW || request.purpose() == Purpose.CHAT) {
            Set<Double> given = numbers(request.userContent() + "\n" + system.text());
            List<String> invented = invented(answer, given);
            if (!invented.isEmpty()) {
                out.add("numbers not in the facts: " + String.join(", ", invented));
            }
        }
        return out;
    }

    static List<String> invented(String answer, Set<Double> given) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(ISO_DATE.matcher(answer).replaceAll(" "));
        while (m.find()) {
            double v = value(m.group());
            if (Math.abs(v) <= SMALL && v == Math.rint(v)) {
                continue;
            }
            if (!known(v, given) && !out.contains(m.group().strip())) {
                out.add(m.group().strip());
            }
        }
        return out;
    }

    /**
     * Every number in a text, without its sign, and the units a writer may fairly convert it to:
     * metres to kilometres, seconds to minutes or hours, the minutes past the hour ("1 h 45") and the
     * seconds past the minute ("17:35").
     */
    public static Set<Double> numbers(String text) {
        Set<Double> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            double v = Math.abs(value(m.group()));
            out.add(v);
            out.add(v / 1000);
            out.add(v / 60);
            out.add(v / 3600);
            out.add(Math.floor(v / 60) % 60);
            out.add(Math.rint(v / 60) % 60);
            out.add(Math.rint(v) % 60);
        }
        return out;
    }

    /** Given as such, or rounded from what was given: 45.04 may be written 45 or 45,0. */
    public static boolean known(double v, Set<Double> given) {
        double a = Math.abs(v);
        for (double g : given) {
            if (a == g || a == Math.rint(g) || a == Math.rint(g * 10) / 10 || a == Math.rint(g * 100) / 100) {
                return true;
            }
        }
        return false;
    }

    private static double value(String written) {
        return Double.parseDouble(written.replaceAll("[ \\u00a0\\u202f]", "").replace(',', '.').toLowerCase(Locale.ROOT));
    }
}
