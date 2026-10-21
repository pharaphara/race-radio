package io.github.pharaphara.raceradio.bot.strength;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The exercise catalogue, shared by every athlete: each exercise has a key that never changes, so a
 * load or a record logged one day attaches to the same exercise, whatever it was called that day.
 * Equipment is one word of the catalogue ({@code aucun} for none); what an athlete owns is read from
 * what they write in plain words.
 */
public final class Exercises {

    public record Exercise(String key, String name, String equipment, String zone) {
    }

    /** Equipment of the catalogue and the words that name it, accents and case aside. */
    static final Map<String, Pattern> EQUIPMENT = new LinkedHashMap<>();

    static {
        EQUIPMENT.put("halteres", Pattern.compile("\\bhalteres?\\b|\\bdumbbells?\\b"));
        EQUIPMENT.put("kettlebell", Pattern.compile("\\bkettle ?bells?\\b|\\bkb\\b"));
        EQUIPMENT.put("elastique", Pattern.compile("\\belastiques?\\b|\\bbandes?\\b|\\bmini ?bands?\\b"));
        EQUIPMENT.put("barre_traction", Pattern.compile("\\bbarre (de|a) tractions?\\b|\\btractions?\\b"));
        EQUIPMENT.put("banc", Pattern.compile("\\bbanc\\b"));
        EQUIPMENT.put("box", Pattern.compile("\\bbox\\b|\\bstep\\b|\\bcaisse\\b"));
        EQUIPMENT.put("trx", Pattern.compile("\\btrx\\b|\\bsangles?\\b"));
        EQUIPMENT.put("medecine_ball", Pattern.compile("\\bmedecine ?ball\\b|\\bmed ?ball\\b|\\bballon lest"));
        EQUIPMENT.put("barre", Pattern.compile("\\bbarre (olympique|droite|de musculation)\\b|\\bdisques?\\b"));
    }

    private final Map<String, Exercise> byKey = new LinkedHashMap<>();

    private Exercises() {
        try (InputStream in = Exercises.class.getClassLoader().getResourceAsStream("strength/exercises.json")) {
            if (in == null) {
                throw new IllegalStateException("the exercise catalogue is missing");
            }
            for (JsonNode n : JsonMapper.builder().build().readTree(in)) {
                byKey.put(n.path("key").asString(), new Exercise(n.path("key").asString(), n.path("name").asString(),
                        n.path("equipment").asString(), n.path("zone").asString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Exercises CATALOGUE = new Exercises();

    public static Exercises catalogue() {
        return CATALOGUE;
    }

    public Optional<Exercise> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** The exercises an athlete can do with what they own; bodyweight ones always. */
    public List<Exercise> doable(Set<String> owned) {
        return byKey.values().stream().filter(e -> e.equipment().equals("aucun") || owned.contains(e.equipment())).toList();
    }

    /** The catalogue's equipment named in plain words: "haltères 2 × 10 kg et un kettlebell". */
    public static Set<String> equipmentIn(String words) {
        String plain = Normalizer.normalize(words == null ? "" : words, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        Set<String> out = new java.util.LinkedHashSet<>();
        EQUIPMENT.forEach((key, pattern) -> {
            if (pattern.matcher(plain).find()) {
                out.add(key);
            }
        });
        return out;
    }

    /** Equipment in French, for messages. */
    public static String french(Set<String> equipment) {
        if (equipment.isEmpty()) {
            return "poids du corps seulement";
        }
        return String.join(", ", equipment.stream().map(e -> switch (e) {
            case "halteres" -> "haltères";
            case "elastique" -> "élastiques";
            case "barre_traction" -> "barre de traction";
            case "medecine_ball" -> "médecine-ball";
            case "barre" -> "barre et disques";
            default -> e;
        }).toList());
    }
}
