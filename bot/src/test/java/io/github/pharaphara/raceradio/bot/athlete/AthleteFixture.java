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
import io.github.pharaphara.raceradio.icu.IntervalsClient;

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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Two athletes on one coach, against a local Telegram that records what is sent, a local
 * Intervals.icu that answers empty lists, and a model that records every prompt and answers what it
 * is told to. The administrator talks in chat 42, the friend in chat 7.
 */
final class AthleteFixture implements AutoCloseable {

    final HttpServer server;
    final String base;
    /** Every request body Telegram received, in order, with its method: "sendMessage {...}". */
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> prompts = new CopyOnWriteArrayList<>();
    /** Prompts that reached the free model, and what it answers. */
    final List<String> freePrompts = new CopyOnWriteArrayList<>();
    final Deque<String> freeAnswers = new ArrayDeque<>();
    /** Answers the model gives in turn; "Réponse." once they run out. */
    final Deque<String> answers = new ArrayDeque<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Europe/Paris"));
    final Path dir;
    final AthleteDb alex;
    final AthleteDb friend;
    final AthleteServices.Shared shared;
    final AthleteRegistry registry;
    final TelegramClient telegram;
    /** A master key made for the test run, never written anywhere. */
    final SecretBox box = SecretBox.of(java.util.Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)));
    final Invitations invitations;
    final AthleteDispatcher dispatcher;

    AthleteFixture(Path dir) throws IOException {
        this.dir = dir;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot", ex -> {
            String path = ex.getRequestURI().getPath();
            sent.add(path.substring(path.lastIndexOf('/') + 1) + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"ok\":true,\"result\":{\"username\":\"race_radio_bot\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/api/v1/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            boolean refused = auth != null && auth.equals("Basic " + java.util.Base64.getEncoder()
                    .encodeToString("API_KEY:wrongwrongwrongwrong".getBytes(StandardCharsets.UTF_8)));
            byte[] out = (path.endsWith("wellness") || path.endsWith("activities") || path.endsWith("events") ? "[]"
                    : path.matches(".*/athlete/i\\d+") ? "{\"id\":\"i7\",\"icu_garmin_upload_workouts\":true}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(refused ? 401 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

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
                String a = answers.isEmpty() ? "Réponse." : answers.removeFirst();
                return new ModelReply(a, "claude-sonnet-5-5", "end_turn", false, Usage.NONE, 1, 0.0);
            }

            @Override
            public String billing() {
                return "subscription";
            }
        };
        ModelTransport freeModel = new ModelTransport() {
            @Override
            public ModelReply send(ModelRequest request, SystemPrompt system) {
                freePrompts.add(request.userContent());
                if (freeAnswers.isEmpty()) {
                    throw new IllegalStateException("free model down");
                }
                return new ModelReply(freeAnswers.removeFirst(), "free/model", "stop", false, Usage.NONE, 1, 0.0);
            }

            @Override
            public String billing() {
                return "api";
            }
        };
        telegram = new TelegramClient("t", HttpClient.newHttpClient(), URI.create(base + "/"));
        shared = new AthleteServices.Shared(alex.jdbc(), telegram, clock, props, SystemPrompt.of("rules"), model,
                new SegmentLibrary(alex.jdbc()), null, false, "", "", URI.create(base + "/api/v1/"), freeModel, "free/model", null);
        Athletes athletes = new Athletes(alex.jdbc());
        registry = new AthleteRegistry(athletes, shared, a -> a.administrator() || a.intervalsKey() == null ? "k" : box.open(a.intervalsKey()));
        invitations = new Invitations(alex.jdbc(), clock);
        CheckIn checkIn = new CheckIn(alex.jdbc(), invitations, box, telegram,
                creds -> new IntervalsClient(creds, HttpClient.newHttpClient(), URI.create(base + "/api/v1/")), id -> { }, clock);
        AdminCommands admin = new AdminCommands(alex.jdbc(), athletes, invitations, registry, telegram, dir, ZoneId.of("Europe/Paris"),
                clock, true);
        dispatcher = new AthleteDispatcher(registry, checkIn, admin, telegram, clock);
    }

    AthleteServices services(AthleteDb db) {
        return registry.services(new Athletes(alex.jdbc()).byId(db.athlete()).orElseThrow());
    }

    static TelegramClient.Update message(long chat, String text) {
        return new TelegramClient.Update(1, chat, text, null, null, null, null, 100);
    }

    static TelegramClient.Update button(long chat, String data) {
        return new TelegramClient.Update(1, chat, null, "cb", data, null, null, 0);
    }

    /** What was sent to one chat, as request bodies. */
    List<String> to(long chat) {
        return sent.stream().filter(b -> b.contains("\"chat_id\":" + chat + ",") || b.contains("\"chat_id\":" + chat + "}")).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
