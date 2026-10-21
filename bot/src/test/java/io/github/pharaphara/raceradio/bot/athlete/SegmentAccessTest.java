package io.github.pharaphara.raceradio.bot.athlete;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

/** The segment library is everyone's to use and the administrator's alone to add to. */
class SegmentAccessTest {

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
    void anAthleteUsesTheLibraryButNeitherDrawsASegmentNorExploresAnArea() {
        f.dispatcher.handleNow(message(7, "/segment nouveau 2026-10-16 25 45 Montée du col"));
        assertThat(f.to(7).getLast()).contains("seul l'administrateur");
        f.dispatcher.handleNow(message(7, "/segment zone Meyrueis 20"));
        assertThat(f.to(7).getLast()).contains("Seul l'administrateur");
        assertThat(f.alex.jdbc().queryForObject("SELECT COUNT(*) FROM segment", Integer.class)).isZero();

        f.dispatcher.handleNow(message(7, "/segment"));
        assertThat(f.to(7).getLast()).doesNotContain("Récupération Strava");
    }
}
