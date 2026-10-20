package io.github.pharaphara.raceradio.bot.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredReviewTest {

    private static final String FACTS = """
            {"power":{"weightedAveragePower":218,"intensity":0.83,"load":98},
             "work":[{"duration":"3 min 23 s","effort":"207 W"},{"duration":"20 min 05 s","effort":"274 W"}],
             "context":{"temperature":24.3}}""";

    @Test
    void referencesBecomeTheFiguresInBoldAndTheShapeIsLaidOut() {
        String answer = """
                {"verdict": "Sortie sans plan avec un long bloc de {work[1].duration} à {work[1].effort}.",
                 "sections": [{"title": "Intensité et charge",
                               "points": ["Puissance pondérée {power.weightedAveragePower} W, intensité {power.intensity}.",
                                          "Il faisait {context.temperature} °C."]}],
                 "next": "Une sortie facile, deux heures au plus."}""";
        String message = StructuredReview.render(FACTS, answer);
        assertThat(message).startsWith("Sortie sans plan avec un long bloc de **20 min 05 s** à **274 W**.")
                .contains("**Intensité et charge**\n• Puissance pondérée **218** W, intensité **0,83**.")
                .contains("**24,3** °C")
                .endsWith("**Et ensuite**\nUne sortie facile, deux heures au plus.");
    }

    @Test
    void aReferenceThatLeadsNowhereOrATypedFigureRefusesTheAnswer() {
        assertThatThrownBy(() -> StructuredReview.render(FACTS, """
                {"verdict": "Charge {power.tss}.", "sections": [{"title": "Exécution", "points": ["Bien."]}], "next": "Repos."}"""))
                .hasMessageContaining("no figure at {power.tss}");
        assertThatThrownBy(() -> StructuredReview.render(FACTS, """
                {"verdict": "274 W soit 105 % de ton seuil.", "sections": [{"title": "Exécution", "points": ["Bien."]}], "next": "Repos."}"""))
                .hasMessageContaining("figure typed by hand: 274").hasMessageContaining("105");
        assertThatThrownBy(() -> StructuredReview.render(FACTS, "Voici mon analyse : bien roulé."))
                .hasMessageContaining("not JSON");
    }

    @Test
    void smallCountsMayBeTyped() {
        String message = StructuredReview.render(FACTS, """
                {"verdict": "Trois blocs, dont 2 au-dessus du seuil.", "sections": [{"title": "Exécution", "points": ["Le 16 octobre."]}], "next": "Repos."}""");
        assertThat(message).contains("dont 2 au-dessus");
    }
}
