package io.github.pharaphara.raceradio.bot.athlete;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two athletes on one coach: each chat reaches its own athlete only, and nothing kept for one
 * athlete ever appears in what is sent to the model for the other.
 */
class AthleteIsolationTest {

    @TempDir
    Path dir;

    private AthleteFixture f;

    @BeforeEach
    void start() throws IOException {
        f = new AthleteFixture(dir);
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    void eachChatReachesItsOwnAthleteAndAStrangerOnlyTheInvitationLine() {
        f.dispatcher.handleNow(message(42, "/aide"));
        f.dispatcher.handleNow(message(7, "/aide"));
        assertThat(f.to(42)).isNotEmpty();
        assertThat(f.to(7)).isNotEmpty();

        f.sent.clear();
        f.dispatcher.handleNow(message(99, "salut"));
        f.dispatcher.handleNow(message(99, "tu es là ?"));
        assertThat(f.sent).hasSize(1);
        assertThat(f.sent.getFirst()).contains("\"chat_id\":99").contains("invitation");
        assertThat(f.registry.forChat(99)).isEmpty();
    }

    @Test
    void nothingKeptForOneAthleteReachesTheModelForTheOther() {
        AthleteServices a = f.services(f.alex);
        AthleteServices b = f.services(f.friend);
        a.memory().addNote(Instant.now(), "MARQUEUR-ALEX genou fragile");
        a.conversation().reply("Question-ALEX sur ma VFC ?");
        b.memory().addNote(Instant.now(), "MARQUEUR-AMI vélo seulement");

        f.prompts.clear();
        b.conversation().reply("Question-AMI : que faire demain ?");
        assertThat(f.prompts).hasSize(1);
        assertThat(f.prompts.getFirst()).contains("MARQUEUR-AMI").contains("Question-AMI")
                .doesNotContain("MARQUEUR-ALEX").doesNotContain("Question-ALEX");

        f.prompts.clear();
        a.conversation().reply("Et pour moi ?");
        assertThat(f.prompts.getFirst()).contains("MARQUEUR-ALEX").contains("Question-ALEX")
                .doesNotContain("MARQUEUR-AMI").doesNotContain("Question-AMI");

        // Their ledgers and folders are their own.
        assertThat(a.dir()).isNotEqualTo(b.dir());
        assertThat(f.alex.jdbc().queryForObject("SELECT COUNT(*) FROM llm_call WHERE athlete_id = ?", Integer.class, f.friend.id())).isEqualTo(1);
        assertThat(f.alex.jdbc().queryForObject("SELECT COUNT(*) FROM llm_call WHERE athlete_id = ?", Integer.class, f.alex.id())).isEqualTo(2);
    }

    @Test
    void theAdministratorsOldFoldersMoveUnderTheirOwnOnce() throws IOException {
        Files.createDirectories(dir.resolve("streams"));
        Files.writeString(dir.resolve("streams").resolve("i1.csv"), "time\n0\n");
        new AthleteRegistry(new Athletes(f.alex.jdbc()), f.shared, a -> "k");
        assertThat(dir.resolve("athletes/1/streams/i1.csv")).exists();
        assertThat(dir.resolve("streams")).doesNotExist();
    }
}
