package io.github.pharaphara.raceradio.bot.llm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coach's standing instructions are pinned. Changing the voice or the knowledge base changes
 * what the coach says, for every decision that follows; that must be a decision, made by updating
 * {@code prompt.version} in the same commit, never a side effect of an unrelated edit.
 */
class PromptVersionTest {

    @Test
    void thePromptIsTheOneThatWasDecided() throws Exception {
        Field f = io.github.pharaphara.raceradio.bot.config.BotConfiguration.class.getDeclaredField("PROMPT");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> parts = (List<String>) f.get(null);
        String pinned = new String(Objects.requireNonNull(getClass().getResourceAsStream("/prompt.version")).readAllBytes(),
                StandardCharsets.UTF_8).strip();
        assertThat(SystemPrompt.fromClasspath(parts).version())
                .as("the system prompt changed: if intended, update bot/src/test/resources/prompt.version")
                .isEqualTo(pinned);
    }
}
