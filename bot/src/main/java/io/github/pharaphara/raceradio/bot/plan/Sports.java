package io.github.pharaphara.raceradio.bot.plan;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An athlete's sports and the words that name them. The sports they practise steer what the coach
 * proposes on its own; a sport they name themselves is always allowed.
 */
public final class Sports {

    private static final Pattern RIDE = Pattern.compile("\\b(velo|bike|home-?trainer|zwift|mywhoosh|vtt|gravel|cyclisme)\\b");
    private static final Pattern RUN = Pattern.compile("\\b(cap|course a pied|courir|footing|jogging|run|trail|fractionne a pied)\\b");
    private static final Pattern SWIM = Pattern.compile("\\b(natation|nage|nager|piscine|swim|crawl|eau libre)\\b");

    private Sports() {
    }

    /** The sports a request names, as families: {@code Ride}, {@code Run}, {@code Swim}. */
    public static Set<String> named(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        if (RIDE.matcher(plain).find()) {
            out.add("Ride");
        }
        if (RUN.matcher(plain).find()) {
            out.add("Run");
        }
        if (SWIM.matcher(plain).find()) {
            out.add("Swim");
        }
        return out;
    }

    /** What the coach may choose: the athlete's sports, plus any the request names. */
    public static Set<String> allowed(Set<String> practised, String request) {
        Set<String> out = new LinkedHashSet<>(practised);
        out.addAll(named(request));
        return out;
    }

    /** The sports in French, for messages and prompts. */
    public static String french(Set<String> sports) {
        return String.join(", ", sports.stream().map(s -> switch (s) {
            case "Ride" -> "vélo";
            case "Run" -> "course à pied";
            case "Swim" -> "natation";
            default -> s;
        }).toList());
    }

    /** Example requests in the athlete's own sports, for /aide and the prompts to /seance. */
    public static List<String> examples(Set<String> sports) {
        List<String> out = new java.util.ArrayList<>();
        if (sports.contains("Run")) {
            out.add("demain footing facile");
        }
        if (sports.contains("Ride")) {
            out.add("jeudi une VO2 à vélo");
            out.add("samedi sortie vélo 2h30");
        }
        if (sports.contains("Run") && out.size() < 3) {
            out.add("samedi sortie longue 1h30");
        }
        if (sports.contains("Swim")) {
            out.add("mercredi natation technique");
        }
        if (out.isEmpty()) {
            out.add("demain une séance facile");
        }
        return out.subList(0, Math.min(3, out.size()));
    }
}
