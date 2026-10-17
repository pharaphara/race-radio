package io.github.pharaphara.raceradio.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole service starts with no secret at all: Telegram stays silent, nothing is written to
 * Intervals.icu (dry run is the default), and no model is called at start-up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RaceRadioApplicationTest {

    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        data = Files.createTempDirectory("race-radio-test");
        r.add("race-radio.data-dir", data::toString);
        r.add("race-radio.intervals.athlete-id", () -> "i42");
        r.add("race-radio.intervals.api-key", () -> "not-a-key");
    }

    @Autowired
    ApplicationContext context;

    @Test
    void startsWithoutSecretsAndInDryRun() {
        assertThat(context.getBean(io.github.pharaphara.raceradio.icu.IntervalsWriter.class).mode())
                .isEqualTo(io.github.pharaphara.raceradio.icu.IntervalsWriter.Mode.DRY_RUN);
        assertThat(Files.exists(data.resolve("race-radio.db"))).isTrue();
    }
}
