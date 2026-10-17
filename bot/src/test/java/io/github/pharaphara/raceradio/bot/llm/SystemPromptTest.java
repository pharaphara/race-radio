package io.github.pharaphara.raceradio.bot.llm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptTest {

    @SuppressWarnings("unchecked")
    private static List<String> parts() throws Exception {
        Field f = io.github.pharaphara.raceradio.bot.config.BotConfiguration.class.getDeclaredField("PROMPT");
        f.setAccessible(true);
        return (List<String>) f.get(null);
    }

    @Test
    void theKnowledgeBaseIsOnTheClasspathAndInThePrompt() throws Exception {
        SystemPrompt p = SystemPrompt.fromClasspath(parts());
        assertThat(p.text()).contains("Seiler 2010").contains("critical swim speed").contains("Section 11");
    }

    @Test
    void thePromptStaysSmallEnoughToBeCheapEvenUncached() throws Exception {
        // About four characters per token: 40,000 characters is about 10,000 tokens.
        assertThat(SystemPrompt.fromClasspath(parts()).text()).hasSizeLessThan(40_000);
    }

    @Test
    void theVersionChangesWhenTheContentChanges() {
        assertThat(SystemPrompt.of("a").version()).isNotEqualTo(SystemPrompt.of("b").version()).hasSize(12);
    }
}
