package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import io.github.pharaphara.raceradio.bot.store.Plans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.button;
import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

/** The way in for a newcomer who has never seen an API key, step by step. */
class CheckInTest {

    @TempDir
    Path dir;

    private AthleteFixture f;
    private static final long NEW = 1234;
    private static final String KEY = "abcdefghij0123456789xyz";

    @BeforeEach
    void start() throws IOException {
        f = new AthleteFixture(dir);
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private String invite() {
        f.dispatcher.handleNow(message(42, "/inviter"));
        String text = f.to(42).getLast();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/start ([A-Z0-9]{8})").matcher(text);
        assertThat(m.find()).as(text).isTrue();
        assertThat(text).contains("https://t.me/race_radio_bot?start=" + m.group(1));
        return m.group(1);
    }

    @Test
    void aNewcomerGoesFromTheCodeToTheirOwnCoachAndTheKeyNeverStaysInClear() {
        String code = invite();
        f.dispatcher.handleNow(message(NEW, "/start " + code));
        assertThat(f.to(NEW).getLast()).contains("prénom");
        f.dispatcher.handleNow(message(NEW, "Paul"));
        f.dispatcher.handleNow(button(NEW, "ck:notyet"));
        assertThat(f.to(NEW).getLast()).contains("intervals.icu");
        f.dispatcher.handleNow(button(NEW, "ck:ready"));
        assertThat(f.to(NEW).getLast()).contains("Developer Settings");

        f.dispatcher.handleNow(message(NEW, "i54321 " + KEY));
        assertThat(f.sent).anySatisfy(s -> assertThat(s).startsWith("deleteMessage").contains("\"message_id\":100"));
        assertThat(f.to(NEW).getLast()).contains("quels sports");

        f.dispatcher.handleNow(button(NEW, "ck:sport:Ride"));
        f.dispatcher.handleNow(button(NEW, "ck:sports-done"));
        assertThat(f.to(NEW).getLast()).contains("C'est parti");

        Athlete paul = new Athletes(f.alex.jdbc()).byChat(NEW).orElseThrow();
        assertThat(paul.name()).isEqualTo("Paul");
        assertThat(paul.intervalsAthleteId()).isEqualTo("i54321");
        assertThat(paul.intervalsKey()).startsWith("v1:").doesNotContain(KEY);
        assertThat(f.box.open(paul.intervalsKey())).isEqualTo(KEY);
        assertThat(new Plans(new AthleteDb(f.alex.jdbc(), paul.id())).sports()).containsExactly("Ride");
        // The key appears nowhere in the database in clear.
        assertThat(f.alex.jdbc().queryForObject("SELECT COUNT(*) FROM checkin", Integer.class)).isZero();

        // From now on the chat is Paul's coach, and the code is spent.
        f.dispatcher.handleNow(message(NEW, "/aide"));
        assertThat(f.to(NEW).getLast()).contains("/seance jeudi une VO2 à vélo").doesNotContain("footing");
        f.dispatcher.handleNow(message(5555, "/start " + code));
        assertThat(f.to(5555).getLast()).contains("n'est pas valable");
    }

    @Test
    void aWrongKeyIsRefusedAndTheMessageStillDeleted() {
        String code = invite();
        f.dispatcher.handleNow(message(NEW, "/start " + code));
        f.dispatcher.handleNow(message(NEW, "Paul"));
        f.dispatcher.handleNow(button(NEW, "ck:ready"));
        f.dispatcher.handleNow(message(NEW, "i54321 wrongwrongwrongwrong"));
        assertThat(f.sent).anySatisfy(s -> assertThat(s).startsWith("deleteMessage"));
        assertThat(f.to(NEW).getLast()).contains("refuse");
        assertThat(new Athletes(f.alex.jdbc()).byChat(NEW)).isEmpty();
    }

    @Test
    void onlyTheAdministratorInvitesAndAnExpiredOrUnknownCodeLetsNobodyIn() {
        f.dispatcher.handleNow(message(7, "/inviter"));
        assertThat(f.alex.jdbc().queryForObject("SELECT COUNT(*) FROM invitation", Integer.class)).isZero();
        f.dispatcher.handleNow(message(NEW, "/start ZZZZ2222"));
        assertThat(f.to(NEW).getLast()).contains("n'est pas valable");
        f.alex.jdbc().update("INSERT INTO invitation (code_hash, created_at, expires_at) VALUES (?, ?, ?)",
                Invitations.hash("OLDC0DE7"), "2026-01-01T00:00:00Z", "2026-01-08T00:00:00Z");
        f.dispatcher.handleNow(message(NEW, "/start OLDC0DE7"));
        assertThat(f.to(NEW).getLast()).contains("n'est pas valable");
    }

    @Test
    void theAdministratorSeesEveryoneAndCanRemoveSomeoneWithEveryTraceOfThem() throws IOException {
        f.alex.jdbc().update("UPDATE athlete SET name = 'Paul' WHERE id = ?", f.friend.id());
        f.services(f.friend).memory().addNote(java.time.Instant.now(), "note de Paul");
        java.nio.file.Files.createDirectories(dir.resolve("athletes/" + f.friend.id() + "/llm"));
        f.dispatcher.handleNow(message(42, "/athletes"));
        assertThat(f.to(42).getLast()).contains("Paul").contains("abonnement");

        f.dispatcher.handleNow(message(42, "/modele Paul gratuit"));
        assertThat(new Athletes(f.alex.jdbc()).byId(f.friend.athlete()).orElseThrow().modelRoute()).isEqualTo("free");

        f.dispatcher.handleNow(message(42, "/retirer Paul"));
        assertThat(f.to(42).getLast()).contains("adm:remove:" + f.friend.id());
        f.dispatcher.handleNow(button(42, "adm:remove:" + f.friend.id()));
        assertThat(new Athletes(f.alex.jdbc()).byId(f.friend.athlete())).isEmpty();
        assertThat(f.alex.jdbc().queryForObject("SELECT COUNT(*) FROM note WHERE athlete_id = ?", Integer.class, f.friend.id())).isZero();
        assertThat(dir.resolve("athletes/" + f.friend.id())).doesNotExist();
        // Their chat is a stranger's again.
        f.dispatcher.handleNow(message(7, "/aide"));
        assertThat(f.to(7).getLast()).contains("invitation");
    }

    @Test
    void secretsAreSealedFreshEachTimeAndRefuseATamperedValue() {
        String a = f.box.seal("secret");
        String b = f.box.seal("secret");
        assertThat(a).isNotEqualTo(b);
        assertThat(f.box.open(a)).isEqualTo("secret");
        String tampered = a.substring(0, a.length() - 2) + (a.endsWith("A") ? "B" : "A") + a.charAt(a.length() - 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.box.open(tampered)).isInstanceOf(IllegalStateException.class);
        SecretBox other = SecretBox.of(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> other.open(a)).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SecretBox.of("dHJvcCBjb3VydA==")).hasMessageContaining("32 bytes");
        assertThat(Map.of()).isEmpty();
    }
}
