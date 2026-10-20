package io.github.pharaphara.raceradio.bot.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackTransportTest {

    private static final SystemPrompt SYSTEM = SystemPrompt.of("coach");
    private static final ModelRequest REQUEST = new ModelRequest(Purpose.REVIEW, "free/model", "medium", 4000, false, "v1", "Analyse");

    /** A stand-in that records the models it was asked for and answers as told. */
    private static final class Stub implements ModelTransport {
        final List<String> asked = new ArrayList<>();
        final Function<ModelRequest, ModelReply> answer;

        Stub(Function<ModelRequest, ModelReply> answer) {
            this.answer = answer;
        }

        @Override
        public ModelReply send(ModelRequest request, SystemPrompt system) {
            asked.add(request.model());
            return answer.apply(request);
        }

        @Override
        public String billing() {
            return "api";
        }
    }

    private static ModelReply reply(String text, String model, boolean refused) {
        return new ModelReply(text, model, "stop", refused, Usage.NONE, 1, null);
    }

    @Test
    void theFirstModelAnswersWhenItCan() {
        Stub first = new Stub(r -> reply("Bien roulé.", "free/model", false));
        Stub second = new Stub(r -> reply("unused", null, false));
        assertThat(new FallbackTransport(first, second, "claude-sonnet-5-5").send(REQUEST, SYSTEM).text()).isEqualTo("Bien roulé.");
        assertThat(second.asked).isEmpty();
    }

    @Test
    void aFailureARefusalOrAnEmptyAnswerGoesToTheSecondUnderItsOwnName() {
        Stub second = new Stub(r -> reply("Par Claude.", null, false));
        Stub down = new Stub(r -> {
            throw new IllegalStateException("HTTP 429");
        });
        ModelReply r = new FallbackTransport(down, second, "claude-sonnet-5-5").send(REQUEST, SYSTEM);
        assertThat(r.text()).isEqualTo("Par Claude.");
        assertThat(r.answeringModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(second.asked).containsExactly("claude-sonnet-5-5");

        new FallbackTransport(new Stub(x -> reply("", "free/model", true)), second, "claude-sonnet-5-5").send(REQUEST, SYSTEM);
        new FallbackTransport(new Stub(x -> reply("  ", "free/model", false)), second, "claude-sonnet-5-5").send(REQUEST, SYSTEM);
        assertThat(second.asked).hasSize(3);
    }
}
