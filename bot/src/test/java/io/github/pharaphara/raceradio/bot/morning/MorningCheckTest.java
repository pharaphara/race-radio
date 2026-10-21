package io.github.pharaphara.raceradio.bot.morning;

import io.github.pharaphara.raceradio.bot.store.AthleteDb;
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
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.FeelStore;
import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.core.readiness.Temperament;
import io.github.pharaphara.raceradio.core.readiness.Verdict;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
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

class MorningCheckTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T05:00:00Z"), PARIS);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @TempDir
    Path dir;

    private AthleteDb jdbc;
    private final List<String> writes = new ArrayList<>();
    private ModelReply nextReply;

    /** Intervals.icu stand-in: a steady month, then this morning as given. */
    private IntervalsClient intervals(Double hrvToday, Integer rhrToday) {
        return new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/")) {
            @Override
            public List<WellnessDay> wellness(LocalDate from, LocalDate to) {
                List<WellnessDay> days = new ArrayList<>();
                for (int i = 40; i >= 1; i--) {
                    days.add(new WellnessDay(TODAY.minusDays(i), 40.0, 38.0, 1.0, 48, 50 * Math.exp(0.1 * Math.sin(i)), 27_000, 80.0, null, null, null, null));
                }
                days.add(new WellnessDay(TODAY, 40.0, 38.0, 1.0, rhrToday, hrvToday, 27_000, 80.0, null, null, null, null));
                return days;
            }

            @Override
            public List<ActivitySummary> activities(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public List<io.github.pharaphara.raceradio.icu.model.CalendarEvent> events(LocalDate from, LocalDate to) {
                return calendar;
            }
        };
    }

    private List<io.github.pharaphara.raceradio.icu.model.CalendarEvent> calendar = List.of();

    @Test
    void anIllnessMarkedTodayRulesOutAnUnqualifiedGreenAndIsAskedAbout() {
        calendar = List.of(new io.github.pharaphara.raceradio.icu.model.CalendarEvent(9L, TODAY.atStartOfDay(),
                TODAY.plusDays(1).atStartOfDay(), "SICK", null, "Fièvre", null, null, null, null, null));
        nextReply = reply("", true);
        MorningCheck.Outcome o = check(intervals(50.0, 48)).run(false);
        assertThat(o.readiness().verdict()).isEqualTo(Verdict.YELLOW);
        assertThat(o.message()).contains("Une maladie est notée dans ton calendrier pour aujourd'hui");
    }

    @Test
    void aOneDayMarkerEndsTheSameDayDespiteTheExclusiveEnd() {
        var e = new io.github.pharaphara.raceradio.icu.model.CalendarEvent(9L, TODAY.minusDays(3).atStartOfDay(),
                TODAY.minusDays(2).atStartOfDay(), "SICK", null, "Gastro", null, null, null, null, null);
        assertThat(MorningCheck.health(TODAY, List.of(e))).isEqualTo(
                new io.github.pharaphara.raceradio.core.readiness.HealthContext(false, 3, "ILLNESS"));
    }

    private MorningCheck check(IntervalsClient icu) {
        ModelTransport t = new ModelTransport() {
            @Override
            public ModelReply send(ModelRequest request, SystemPrompt system) {
                return nextReply;
            }

            @Override
            public String billing() {
                return "subscription";
            }
        };
        CoachModel model = new CoachModel(t, SystemPrompt.of("rules"), new Ledger(jdbc), new Journal(dir.resolve("llm")),
                new Pricing(Map.<String, RaceRadioProperties.Prices>of()), "claude-sonnet-5-5", true, new BigDecimal("10"), CLOCK, PARIS);
        IntervalsWriter writer = new IntervalsWriter(icu, IntervalsWriter.Mode.DRY_RUN, writes::add);
        return new MorningCheck(icu, writer, model, new FeelStore(jdbc), new DecisionLog(jdbc), Temperament.COMMITTED, "low", CLOCK);
    }

    @BeforeEach
    void setUp() {
        jdbc = TestDb.in(dir);
    }

    private static ModelReply reply(String text, boolean refused) {
        return new ModelReply(text, "claude-sonnet-5-5", refused ? "refusal" : "end_turn", refused, Usage.NONE, 1, 0.001);
    }

    @Test
    void anOrdinaryMorningIsWordedByTheModel() {
        nextReply = reply("🟢 Entraînement prévu\nVFC et sommeil dans ta norme : séance maintenue.", false);
        MorningCheck.Outcome o = check(intervals(50.0, 48)).run(true);
        assertThat(o.readiness().verdict()).isEqualTo(Verdict.GREEN);
        assertThat(o.worded()).isTrue();
        assertThat(o.message()).startsWith("🟢 Entraînement prévu").contains("séance maintenue");
    }

    @Test
    void whenTheModelDeclinesTheDeterministicFrenchTextIsSent() {
        nextReply = reply("", true);
        MorningCheck.Outcome o = check(intervals(22.0, 63)).run(true);
        assertThat(o.worded()).isFalse();
        assertThat(o.message()).startsWith("🔴").contains("VFC de 22 ms").contains("FC de repos à 63");
    }

    @Test
    void theModelCannotChangeTheVerdict() {
        nextReply = reply("🟢 Entraînement prévu\nFonce !", false);
        MorningCheck.Outcome o = check(intervals(22.0, 63)).run(true);
        assertThat(o.message()).startsWith("🔴 Repos ou récupération active").doesNotContain("🟢");
    }

    @Test
    void theMorningIsPublishedAsANoteAndRecordedOnlyOncePerDay() {
        nextReply = reply("🟢 Entraînement prévu\nRAS.", false);
        MorningCheck c = check(intervals(50.0, 48));
        c.run(true);
        c.run(true);
        c.run(false);
        assertThat(writes).hasSize(1).first().asString().startsWith("DRY RUN POST athlete/i42/events").contains("\"category\":\"NOTE\"");
        assertThat(new DecisionLog(jdbc).latest()).get().satisfies(d -> {
            assertThat(d.verdict()).isEqualTo("GREEN");
            assertThat(d.day()).isEqualTo("2026-10-17");
            assertThat(d.evidenceJson()).contains("\"model\":\"readiness/two-layer-graded\"");
        });
    }

    @Test
    void aMorningWithoutDataSaysWhatIsMissingInsteadOfGuessing() {
        nextReply = reply("", true);
        MorningCheck.Outcome o = check(intervals(null, null)).run(false);
        assertThat(o.readiness().signals()).isEmpty();
        assertThat(o.message()).contains("Données manquantes").contains("la VFC de ce matin");
    }
}
