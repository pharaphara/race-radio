package io.github.pharaphara.raceradio.bot.commands;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuickRepliesTest {

    @Test
    void aButtonCarriesANumberAndGivesBackItsText() {
        QuickReplies q = new QuickReplies();
        List<Map<String, String>> rows = q.rows(List.of("Que faire demain ?", "Analyse plus détaillée"));
        assertThat(rows).hasSize(2).allMatch(r -> r.size() == 1);
        String data = rows.getFirst().get("Que faire demain ?");
        assertThat(data).matches("ask:\\d+").hasSizeLessThanOrEqualTo(64);
        assertThat(q.handles(data)).isTrue();
        assertThat(q.text(data)).contains("Que faire demain ?");
    }

    @Test
    void atMostThreeOneARowAndNothingTooLongForAButton() {
        QuickReplies q = new QuickReplies();
        assertThat(q.rows(List.of("a", "b", "c", "d"))).hasSize(3);
        assertThat(q.rows(List.of("x".repeat(QuickReplies.MAX_LABEL + 1), "", "ok"))).hasSize(1);
    }

    @Test
    void onlyTheMostRecentAreKept() {
        QuickReplies q = new QuickReplies();
        String first = q.rows(List.of("première")).getFirst().get("première");
        for (int i = 0; i < QuickReplies.KEPT; i++) {
            q.rows(List.of("q" + i));
        }
        assertThat(q.text(first)).isEmpty();
    }
}
