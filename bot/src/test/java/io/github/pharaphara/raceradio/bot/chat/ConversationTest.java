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
                return new ModelReply(modelReply, "claude-sonnet-5-5", "end_turn", false, Usage.NONE, 1, 0.01);
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
        return new Conversation(model, morning, new DecisionLog(jdbc), new Conversations(jdbc), CLOCK, history);
    }

    private String modelReply = "Repos ce soir, ça vaut mieux.";
    private final List<String> trendsSent = new ArrayList<>();
    private History history = new History() {
        @Override
        public List<String> recentDays(int days) {
            return List.of("2026-10-16 VFC 30 ms, FC de repos 53");
        }

        @Override
        public List<String> recentSessions(int days) {
            return List.of();
        }

        @Override
        public java.util.Optional<String> day(LocalDate day) {
            return day.equals(LocalDate.of(2026, 9, 12)) ? java.util.Optional.of("Samedi 12 septembre 2026\nMatin : VFC 52 ms")
                    : java.util.Optional.empty();
        }

        @Override
        public boolean trend(String metricKey, int months) {
            trendsSent.add(metricKey + " " + months);
            return metricKey.equals("velo_efficacite");
        }

        @Override
        public List<String> metrics() {
            return List.of("velo_efficacite: efficacité aérobie");
        }
    };

    @Test
    void recentMorningsAreInTheContextAndOlderQuestionsAreAnsweredByTheProgram() {
        Conversation c = conversation();
        c.answer("Ma VFC d'hier soir ?");
        assertThat(prompts.getLast()).contains("VFC 30 ms").contains("velo_efficacite: efficacité aérobie");

        modelReply = "JOUR 2026-09-12";
        assertThat(c.answer("Et le 12 septembre ?")).isEqualTo("Samedi 12 septembre 2026\nMatin : VFC 52 ms");
        modelReply = "JOUR 2026-01-01";
        assertThat(c.answer("Et le 1er janvier ?")).contains("rien dans mon archive");

        modelReply = "TENDANCE velo_efficacite 12";
        assertThat(c.answer("Mon W/bpm sur un an ?")).isNull();
        assertThat(trendsSent).containsExactly("velo_efficacite 12");
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

    @Test
    void followUpsComeAsSuggestionsAndAreLeftOutOfTheText() {
        modelReply = """
                Ta VFC est à **30 ms**, sous ta moyenne.
                SUGGESTIONS:
                - Regarde aussi mon sommeil
                - « Propose la séance de jeudi »
                - Compare à ma sortie du 4 octobre
                - une de trop""";
        Conversation.Reply r = conversation().reply("Ma VFC ?");
        assertThat(r.text()).isEqualTo("Ta VFC est à **30 ms**, sous ta moyenne.");
        assertThat(r.suggestions()).containsExactly("Regarde aussi mon sommeil", "Propose la séance de jeudi", "Compare à ma sortie du 4 octobre");
        assertThat(r.action()).isNull();
    }

    @Test
    void aStructuredAnswerIsFilledFromTheContextAndItsDelegationIsCarriedOut() {
        Conversation c = conversation().structured(true);
        String prompt = c.prompt("Ma VFC ?");
        assertThat(prompt).contains(Conversation.STRUCTURED_MARKER).doesNotContain("SUGGESTIONS:");

        modelReply = """
                {"reply": "Ta VFC est à 30 ms, sous ta moyenne.", "suggestions": ["Regarde aussi mon sommeil", "Propose la séance de jeudi", "Compare à ma sortie du 4 octobre"], "delegate": ""}""";
        Conversation.Reply r = c.reply("Ma VFC ?");
        assertThat(r.text()).isEqualTo("Ta VFC est à 30 ms, sous ta moyenne.");
        assertThat(r.suggestions()).hasSize(3);

        modelReply = """
                {"reply": "", "suggestions": [], "delegate": "SEANCE jeudi une VO2 à vélo"}""";
        assertThat(c.reply("Propose la séance de jeudi").action()).isEqualTo(new Conversation.Action("SEANCE", "jeudi une VO2 à vélo"));
    }

    @Test
    void aFigureTheContextDoesNotHoldIsRefused() {
        tools.jackson.databind.JsonNode context = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree("{\"lastMornings\": [\"2026-10-16 VFC 30 ms\"], \"power\": {\"load\": 98}}");
        assertThat(Conversation.fromJson("{\"reply\": \"Charge {power.load}, VFC 30 ms.\", \"suggestions\": []}", context))
                .startsWith("Charge **98**, VFC 30 ms.");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        Conversation.fromJson("{\"reply\": \"Ta charge monte de 45 %.\", \"suggestions\": []}", context))
                .hasMessageContaining("figure typed by hand: 45");
    }

    @Test
    void askingForAWorkoutOpensADraftInsteadOfAnswering() {
        modelReply = "SEANCE jeudi une VO2 à vélo";
        Conversation.Reply r = conversation().reply("Propose la séance de jeudi");
        assertThat(r.text()).isNull();
        assertThat(r.action()).isEqualTo(new Conversation.Action("SEANCE", "jeudi une VO2 à vélo"));
        modelReply = "SEMAINE";
        assertThat(conversation().reply("Programme ma semaine").action()).isEqualTo(new Conversation.Action("SEMAINE", ""));
    }

    @Test
    void oneSessionOrTwoSideBySideAreAnsweredByTheProgram() {
        history = new History() {
            @Override public List<String> recentDays(int days) { return List.of(); }
            @Override public List<String> recentSessions(int days) { return List.of(); }
            @Override public java.util.Optional<String> day(LocalDate day) { return java.util.Optional.empty(); }
            @Override public boolean trend(String metricKey, int months) { return false; }
            @Override public List<String> metrics() { return List.of(); }
            @Override public java.util.Optional<String> activity(LocalDate day) {
                return java.util.Optional.of("« Sortie » (Ride)\nVitesse en mouvement : 29,1 km/h");
            }
            @Override public java.util.Optional<String> compare(LocalDate one, LocalDate two) {
                return java.util.Optional.of("Vitesse en mouvement : 27,0 km/h → 29,1 km/h");
            }
        };
        modelReply = "ACTIVITE 2026-10-16";
        assertThat(conversation().answer("Ma moyenne sans les pauses le 16 ?")).contains("29,1 km/h");
        modelReply = "COMPARE 2026-03-04 2026-10-16";
        assertThat(conversation().answer("Compare à ma sortie du 4 mars")).contains("27,0 km/h → 29,1 km/h");
    }
}
