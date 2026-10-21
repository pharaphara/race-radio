package io.github.pharaphara.raceradio.bot.strength;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A strength session as timed blocks: the watch times each work and rest, and the note says which
 * exercise comes when. Intervals.icu keeps the timing of such steps but not their words (checked on
 * the real calendar), so the exercises go in the note, in order, with their repetitions.
 *
 * @param blocks  warm-up, circuits, cool-down, in order
 */
public record StrengthWorkout(String name, List<Block> blocks) {

    /** A block repeated {@code rounds} times. */
    public record Block(String title, int rounds, List<Item> items) {
    }

    /** One exercise, or a rest when {@code exercise} is null; reps are a guide, the watch times seconds. */
    public record Item(String exercise, int seconds, Integer reps) {

        public boolean rest() {
            return exercise == null;
        }
    }

    public int seconds() {
        return blocks.stream().mapToInt(b -> b.rounds() * b.items().stream().mapToInt(Item::seconds).sum()).sum();
    }

    /** Reads the model's JSON; the shape is checked by {@link #problems}. */
    public static StrengthWorkout of(JsonNode w) {
        List<Block> blocks = new ArrayList<>();
        for (JsonNode b : w.path("blocks")) {
            List<Item> items = new ArrayList<>();
            for (JsonNode i : b.path("items")) {
                String exercise = i.path("exercise").isString() && !i.path("exercise").asString().isBlank() ? i.path("exercise").asString() : null;
                items.add(new Item(exercise, i.path("seconds").asInt(0), i.path("reps").isNumber() ? i.path("reps").asInt() : null));
            }
            blocks.add(new Block(b.path("title").asString("Bloc"), Math.max(1, b.path("rounds").asInt(1)), items));
        }
        return new StrengthWorkout(w.path("name").asString("Renforcement"), blocks);
    }

    /** What breaks the rules: an exercise not in the catalogue, or not doable with what the athlete owns, or odd timings. */
    public List<String> problems(Exercises catalogue, Set<String> owned) {
        List<String> out = new ArrayList<>();
        if (blocks.isEmpty()) {
            out.add("no block");
        }
        for (Block b : blocks) {
            if (b.rounds() > 6) {
                out.add(b.title() + ": " + b.rounds() + " rounds, 6 at most");
            }
            if (b.items().isEmpty()) {
                out.add(b.title() + ": empty");
            }
            for (Item i : b.items()) {
                if (i.seconds() < 10 || i.seconds() > 900) {
                    out.add(b.title() + ": " + i.seconds() + " s, between 10 s and 15 min");
                }
                if (!i.rest()) {
                    var e = catalogue.get(i.exercise());
                    if (e.isEmpty()) {
                        out.add("unknown exercise " + i.exercise());
                    } else if (!e.get().equipment().equals("aucun") && !owned.contains(e.get().equipment())) {
                        out.add(e.get().key() + " needs " + e.get().equipment() + ", which the athlete does not have");
                    }
                }
            }
        }
        if (seconds() > 90 * 60) {
            out.add(seconds() / 60 + " min, 90 at most");
        }
        return out;
    }

    /** The order of the exercises, for the preview and the note: "Circuit, 3 tours : squat gobelet 40 s (≈ 12)…". */
    public List<String> lines(Exercises catalogue) {
        List<String> out = new ArrayList<>();
        for (Block b : blocks) {
            StringBuilder line = new StringBuilder(b.title()).append(b.rounds() > 1 ? ", " + b.rounds() + " tours" : "").append(" : ");
            List<String> parts = new ArrayList<>();
            for (Item i : b.items()) {
                parts.add(i.rest() ? "récup " + duration(i.seconds())
                        : catalogue.get(i.exercise()).map(Exercises.Exercise::name).orElse(i.exercise()) + " " + duration(i.seconds())
                        + (i.reps() == null ? "" : " (≈ " + i.reps() + ")"));
            }
            out.add(line.append(String.join(" · ", parts)).toString());
        }
        return out;
    }

    /**
     * The steps as Intervals.icu reads them: timed steps without a target. Exercise names stay out of
     * the steps, which would drop them anyway, so no word can be misread as an intensity.
     */
    public String steps() {
        StringBuilder b = new StringBuilder();
        for (Block block : blocks) {
            String title = block.title().replaceAll("[^\\p{L} ]", "").strip();
            b.append(title.isEmpty() ? "Bloc" : title).append(block.rounds() > 1 ? " " + block.rounds() + "x" : "").append('\n');
            for (Item i : block.items()) {
                b.append("- ").append(step(i.seconds())).append('\n');
            }
            b.append('\n');
        }
        return b.toString().strip();
    }

    private static String step(int seconds) {
        return seconds % 60 == 0 ? seconds / 60 + "m" : seconds < 60 ? seconds + "s" : seconds / 60 + "m" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    static String duration(int seconds) {
        return seconds >= 60 && seconds % 60 == 0 ? seconds / 60 + " min"
                : seconds >= 60 ? String.format(Locale.ROOT, "%d min %02d", seconds / 60, seconds % 60) : seconds + " s";
    }
}
