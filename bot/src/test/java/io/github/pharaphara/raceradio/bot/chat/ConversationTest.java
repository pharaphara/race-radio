package io.github.pharaphara.raceradio.bot.chat;

import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.bot.config.RaceRadioProperties;
import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.Journal;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.ModelRequest;
import io.github.pharaphara.raceradio.bot.llm.ModelTransport;
import io.github.pharaphara.raceradio.bot.llm.Pricing;
import io.github.pharaphara.raceradio.bot.llm.SystemPrompt;
import io.github.pharaphara.raceradio.bot.llm.Usage;
import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.store.Conversations;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.FeelStore;
import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.core.readiness.Temperament;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T16:00:00Z"), PARIS);

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private final List<String> prompts = new ArrayList<>();
    private String budget = "10";

    @BeforeEach
    void setUp() {
        jdbc = TestDb.in(dir);
    }

    private Conversation conversation() {
        IntervalsClient icu = new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/")) {
            @Override
            public List<WellnessDay> wellness(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public List<ActivitySummary> activities(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public List<CalendarEvent> events(LocalDate from, LocalDate to) {
                return List.of();
            }
        };
        ModelTransport t = new ModelTransport() {
            @Override
            public ModelReply send(ModelRequest request, SystemPrompt system) {
                prompts.add(request.userContent());
                return new ModelReply("Repos ce soir, ça vaut mieux.", "claude-sonnet-5-5", "end_turn", false, Usage.NONE, 1, 0.01);
            }

            @Override
            public String billing() {
                return "subscription";
            }
        };
        CoachModel model = new CoachModel(t, SystemPrompt.of("rules"), new Ledger(jdbc), new Journal(dir.resolve("llm")),
                new Pricing(Map.<String, RaceRadioProperties.Prices>of()), "claude-sonnet-5-5", true, new BigDecimal(budget), CLOCK, PARIS);
        MorningCheck morning = new MorningCheck(icu, new IntervalsWriter(icu, IntervalsWriter.Mode.DRY_RUN, s -> { }), model,
                new FeelStore(jdbc), new DecisionLog(jdbc), Temperament.COMMITTED, "low", CLOCK);
        return new Conversation(model, morning, new DecisionLog(jdbc), new Conversations(jdbc), CLOCK);
    }

    @Test
    void anAnswerUsesTheNotesAndTheRecentTurnsAndIsRemembered() {
        Conversations memory = new Conversations(jdbc);
        memory.addNote(CLOCK.instant(), "mollet gauche sensible");
        Conversation c = conversation();
        c.answer("Je fais quoi ce soir ?");
        String reply = c.answer("Et demain ?");
        assertThat(reply).isEqualTo("Repos ce soir, ça vaut mieux.");
        assertThat(prompts.getLast()).contains("mollet gauche sensible").contains("Je fais quoi ce soir ?").endsWith("Et demain ?");
        assertThat(memory.last(10)).extracting(Conversations.Turn::role).containsExactly("athlete", "coach", "athlete", "coach");
    }

    @Test
    void contextFromTheProgramIsFencedButTheAthletesOwnMessageIsNot() {
        conversation().answer("Pourquoi rouge ?");
        assertThat(prompts.getFirst()).contains("<data source=\"race-radio.context\">").endsWith("Pourquoi rouge ?");
    }

    @Test
    void withTheBudgetSpentTheCoachSaysSoAndPointsToWhatStillWorks() {
        budget = "0";
        assertThat(conversation().answer("Salut")).contains("budget").contains("/forme");
        assertThat(prompts).isEmpty();
    }

    @Test
    void notesCanBeWithdrawn() {
        Conversations memory = new Conversations(jdbc);
        long id = memory.addNote(CLOCK.instant(), "genou");
        assertThat(memory.withdraw(id)).isTrue();
        assertThat(memory.withdraw(id)).isFalse();
        assertThat(memory.activeNotes()).isEmpty();
    }
}
