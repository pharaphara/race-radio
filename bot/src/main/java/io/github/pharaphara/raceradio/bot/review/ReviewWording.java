package io.github.pharaphara.raceradio.bot.review;

import java.util.Locale;
import java.util.Map;

/** The session review in French without the model: the facts, plainly. */
public final class ReviewWording {

    private static final Map<String, String> DEVIATIONS = Map.of(
            "AS_PLANNED", "conforme",
            "LONGER", "plus long",
            "SHORTER", "plus court",
            "HARDER", "plus dur",
            "EASIER", "plus facile",
            "MISSING", "non fait",
            "EXTRA", "hors plan");

    private ReviewWording() {
    }

    public static String deterministic(SessionFacts f) {
        StringBuilder b = new StringBuilder("Séance " + sport(f.sport()));
        if (f.movingSeconds() != null) {
            b.append(", ").append(Formats.duration(f.movingSeconds()));
        }
        if (f.load() != null) {
            b.append(", charge ").append(f.load());
        }
        b.append('.');
        if (!f.enoughData()) {
            return b.append("\nPas assez de données pour juger cette séance. Comment l'as-tu ressentie, et à quel point était-elle dure ?").toString();
        }
        if (f.work().isEmpty()) {
            b.append("\nPas de bloc de travail : effort régulier ou en dessous de l'endurance.");
        } else {
            b.append("\nStructure ").append("DEVICE_LAPS".equals(f.structureSource()) ? "reprise des tours de ta montre" : "détectée")
                    .append(" : ").append(String.join(", ", f.repetitions())).append('.');
        }
        for (SessionFacts.ComparisonFact c : f.againstPlan()) {
            if (c.deviations().equals(java.util.List.of("EXTRA"))) {
                continue;
            }
            b.append("\n• ").append(c.planned() == null ? "hors plan" : "prévu " + c.planned())
                    .append(c.done() == null ? "" : " · fait " + c.done())
                    .append(" · ").append(String.join(", ", c.deviations().stream().map(d -> DEVIATIONS.getOrDefault(d, d)).toList()));
        }
        if (f.power() != null) {
            b.append(String.format(Locale.FRANCE, "%nPuissance pondérée %d W, intensité %.2f, variabilité %.2f.",
                    f.power().weightedAveragePower(), f.power().intensity(), f.power().variability()));
            if (f.power().wPrimeExceeded()) {
                b.append(" Réserve anaérobie dépassée : ton seuil ou ta réserve déclarés sont peut-être trop bas.");
            }
        }
        if (f.aerobicDecouplingPercent() != null) {
            b.append(String.format(Locale.FRANCE, "%nDérive cardiaque %.1f %% sur l'effort régulier.", f.aerobicDecouplingPercent()));
        }
        if (f.distancePerStrokeMetres() != null) {
            b.append(String.format(Locale.FRANCE, "%nDistance par mouvement de bras : %.2f m.", f.distancePerStrokeMetres()));
        }
        return b.toString();
    }

    private static String sport(String type) {
        if (EffortSignal.BIKE.contains(type)) {
            return "vélo";
        }
        if (EffortSignal.RUN.contains(type)) {
            return "course";
        }
        if (EffortSignal.SWIM.contains(type)) {
            return "natation";
        }
        return type == null ? "" : type.toLowerCase(Locale.ROOT);
    }
}
