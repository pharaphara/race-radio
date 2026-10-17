package io.github.pharaphara.raceradio.bot.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramClientTest {

    @Test
    void longTextIsSplitOnLineBoundariesWithinTheLimit() {
        String line = "x".repeat(1000);
        String text = String.join("\n", line, line, line, line, line, line);
        var parts = TelegramClient.split(text);
        assertThat(parts).hasSize(2).allMatch(p -> p.length() <= TelegramClient.MAX_MESSAGE);
        assertThat(String.join("\n", parts)).isEqualTo(text);
    }

    @Test
    void aSingleOverlongLineIsCutRatherThanRejected() {
        assertThat(TelegramClient.split("y".repeat(9000))).hasSize(3).allMatch(p -> p.length() <= TelegramClient.MAX_MESSAGE);
    }
}
