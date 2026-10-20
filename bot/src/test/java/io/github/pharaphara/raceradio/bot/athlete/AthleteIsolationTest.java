package io.github.pharaphara.raceradio.bot.athlete;

import com.sun.net.httpserver.HttpServer;
import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.bot.config.RaceRadioProperties;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.ModelRequest;
import io.github.pharaphara.raceradio.bot.llm.ModelTransport;
import io.github.pharaphara.raceradio.bot.llm.SystemPrompt;
import io.github.pharaphara.raceradio.bot.llm.Usage;
import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import io.github.pharaphara.raceradio.bot.strava.SegmentLibrary;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.core.readiness.Temperament;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two athletes on one coach, against a local Telegram, a local Intervals.icu and a model that
 * records every prompt: each chat reaches its own athlete only, and nothing kept for one athlete
 * ever appears in what is sent to the model for the other.
 */
class AthleteIsolationTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private AthleteRegistry registry;
    private AthleteServices.Shared shared;
    private AthleteDispatcher dispatcher;
    private AthleteDb alex;
    private AthleteDb friend;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Europe/Paris"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot", ex -> {
            sent.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"ok\":true,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/api/v1/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] out = (path.endsWith("wellness") || path.endsWith("activities") || path.endsWith("events") ? "[]" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        alex = TestDb.in(dir);
        friend = TestDb.another(alex, 7);
        RaceRadioProperties props = new RaceRadioProperties(true, ZoneId.of("Europe/Paris"), dir, Temperament.COMMITTED,
                new RaceRadioProperties.Telegram("t", 42), new RaceRadioProperties.Intervals("i42", "k"),
                new RaceRadioProperties.Model("subscription", "claude", "", "claude-sonnet-5-5", "low", "medium", false,
                        new BigDecimal("10"), Map.of(), 30, "", "", "", "", "off", "text"),
                new RaceRadioProperties.Schedule(LocalTime.of(7, 0), LocalTime.of(6, 0), LocalTime.of(23, 0), 15),
                new RaceRadioProperties.Athlete(null, 5));
        ModelTransport model = new ModelTransport() {
            @Override
            public ModelReply send(ModelRequest request, SystemPrompt system) {
                prompts.add(request.userContent());
                return new ModelReply("Réponse.", "claude-sonnet-5-5", "end_turn", false, Usage.NONE, 1, 0.0);
            }

            @Override
            public String billing() {
                return "subscription";
            }
        };
        TelegramClient telegram = new TelegramClient("t", HttpClient.newHttpClient(), URI.create(base + "/"));
        shared = new AthleteServices.Shared(alex.jdbc(), telegram, clock, props, SystemPrompt.of("rules"), model,
                new SegmentLibrary(alex.jdbc()), null, false, "", "", URI.create(base + "/api/v1/"));
        registry = new AthleteRegistry(new Athletes(alex.jdbc()), shared, a -> "k");
        dispatcher = new AthleteDispatcher(registry, telegram, clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static TelegramClient.Update message(long chat, String text) {
        return new TelegramClient.Update(1, chat, text, null, null, null, null);
    }

    @Test
    void eachChatReachesItsOwnAthleteAndAStrangerOnlyTheInvitationLine() {
        dispatcher.handleNow(message(42, "/aide"));
        dispatcher.handleNow(message(7, "/aide"));
        assertThat(sent).anySatisfy(b -> assertThat(b).contains("\"chat_id\":42"));
        assertThat(sent).anySatisfy(b -> assertThat(b).contains("\"chat_id\":7"));

        sent.clear();
        dispatcher.handleNow(message(99, "salut"));
        dispatcher.handleNow(message(99, "tu es là ?"));
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("\"chat_id\":99").contains("invitation");
        assertThat(registry.forChat(99)).isEmpty();
    }

    @Test
    void nothingKeptForOneAthleteReachesTheModelForTheOther() {
        AthleteServices a = registry.services(new Athletes(alex.jdbc()).byId(alex.athlete()).orElseThrow());
        AthleteServices f = registry.services(new Athletes(alex.jdbc()).byId(friend.athlete()).orElseThrow());
        a.memory().addNote(Instant.now(), "MARQUEUR-ALEX genou fragile");
        a.conversation().reply("Question-ALEX sur ma VFC ?");
        f.memory().addNote(Instant.now(), "MARQUEUR-AMI vélo seulement");

        prompts.clear();
        f.conversation().reply("Question-AMI : que faire demain ?");
        assertThat(prompts).hasSize(1);
        assertThat(prompts.getFirst()).contains("MARQUEUR-AMI").contains("Question-AMI")
                .doesNotContain("MARQUEUR-ALEX").doesNotContain("Question-ALEX");

        prompts.clear();
        a.conversation().reply("Et pour moi ?");
        assertThat(prompts.getFirst()).contains("MARQUEUR-ALEX").contains("Question-ALEX")
                .doesNotContain("MARQUEUR-AMI").doesNotContain("Question-AMI");

        // Their ledgers and journals are their own.
        assertThat(a.dir()).isNotEqualTo(f.dir());
        assertThat(alex.jdbc().queryForObject("SELECT COUNT(*) FROM llm_call WHERE athlete_id = ?", Integer.class, friend.id())).isEqualTo(1);
        assertThat(alex.jdbc().queryForObject("SELECT COUNT(*) FROM llm_call WHERE athlete_id = ?", Integer.class, alex.id())).isEqualTo(2);
    }

    @Test
    void theAdministratorsOldFoldersMoveUnderTheirOwnOnce() throws IOException {
        java.nio.file.Files.createDirectories(dir.resolve("streams"));
        java.nio.file.Files.writeString(dir.resolve("streams").resolve("i1.csv"), "time\n0\n");
        new AthleteRegistry(new Athletes(alex.jdbc()), shared, a -> "k");
        assertThat(dir.resolve("athletes/1/streams/i1.csv")).exists();
        assertThat(dir.resolve("streams")).doesNotExist();
    }
}
