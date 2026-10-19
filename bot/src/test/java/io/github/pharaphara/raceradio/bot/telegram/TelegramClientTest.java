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

    @Test
    void theModelsLightMarkdownBecomesSafeTelegramHtml() {
        String text = """
                ### Exécution
                **Intensité et charge**
                - Puissance pondérée **219 W** sur un parcours à < 600 m & plus
                * FC moyenne **162**""";
        assertThat(TelegramClient.html(text)).isEqualTo("""
                <b>Exécution</b>
                <b>Intensité et charge</b>
                • Puissance pondérée <b>219 W</b> sur un parcours à &lt; 600 m &amp; plus
                • FC moyenne <b>162</b>""");
        assertThat(TelegramClient.plain(text)).doesNotContain("**").doesNotContain("###").contains("• FC moyenne 162");
    }

    @Test
    void textThatLooksLikeMarkupIsEscapedNotInterpreted() {
        assertThat(TelegramClient.html("<script>alert(1)</script> et **gras")).isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt; et **gras");
    }
}
