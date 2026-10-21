package io.github.pharaphara.raceradio.bot.athlete;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

/** Each athlete's messages are written by the model the administrator chose for them, and no other. */
class ModelRouteTest {

    @TempDir
    Path dir;

    private AthleteFixture f;

    @BeforeEach
    void start() throws IOException {
        f = new AthleteFixture(dir);
        f.alex.jdbc().update("UPDATE athlete SET name = 'Paul' WHERE id = ?", f.friend.id());
        f.dispatcher.handleNow(message(42, "/modele Paul gratuit"));
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    void anAthleteOnTheFreeModelIsAnsweredByItInCheckedJsonAndTheAdministratorByTheSubscription() {
        f.freeAnswers.add("""
                {"reply": "Une sortie facile demain.", "suggestions": ["Et après-demain ?", "Ma VFC ?", "Mon sommeil ?"], "delegate": ""}""");
        var paul = f.services(f.friend).conversation().reply("Que faire demain ?");
        assertThat(paul.text()).isEqualTo("Une sortie facile demain.");
        assertThat(paul.suggestions()).hasSize(3);
        assertThat(f.freePrompts).hasSize(1);
        assertThat(f.freePrompts.getFirst()).contains("Answer with JSON only");
        assertThat(f.prompts).isEmpty();

        f.services(f.alex).conversation().reply("Et moi ?");
        assertThat(f.prompts).hasSize(1);
        assertThat(f.prompts.getFirst()).doesNotContain("Answer with JSON only");
        assertThat(f.freePrompts).hasSize(1);
    }

    @Test
    void whenTheFreeModelFailsTheSubscriptionAnswersOnlyIfAllowed() {
        var without = f.services(f.friend).conversation().reply("Que faire demain ?");
        assertThat(without.text()).contains("Je n'arrive pas à joindre le modèle");
        assertThat(f.prompts).isEmpty();

        f.dispatcher.handleNow(message(42, "/secours Paul oui"));
        // The subscription gets the same request, the JSON one, and answers it as asked.
        f.answers.add("""
                {"reply": "Par l'abonnement.", "suggestions": [], "delegate": ""}""");
        var with = f.services(f.friend).conversation().reply("Que faire demain ?");
        assertThat(with.text()).isEqualTo("Par l'abonnement.");
        assertThat(f.prompts).hasSize(1);

        f.dispatcher.handleNow(message(42, "/athletes"));
        assertThat(f.to(42).getLast()).contains("modèle gratuit (secours abonnement)");
    }
}
