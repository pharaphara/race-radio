package io.github.pharaphara.raceradio.bot.strava;

import com.sun.net.httpserver.HttpServer;
import io.github.pharaphara.raceradio.bot.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StravaClientTest {

    @TempDir
    Path dir;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A clock that can be moved forward. */
    private static final class MovingClock extends Clock {
        Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aQuotaCountFromAPastWindowNoLongerBlocks() throws IOException {
        AtomicInteger reads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            byte[] out = "{\"access_token\":\"a\",\"expires_at\":4102444800,\"refresh_token\":\"r\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/api/", ex -> {
            reads.incrementAndGet();
            // Strava reports 95 reads used in this quarter hour, over a limit of 100.
            ex.getResponseHeaders().add("x-readratelimit-usage", "95,300");
            ex.getResponseHeaders().add("x-readratelimit-limit", "100,1000");
            byte[] out = "[]".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        MovingClock clock = new MovingClock(Instant.parse("2026-10-19T08:01:00Z"));
        StravaClient strava = new StravaClient("1", "s", "r", TestDb.in(dir), clock, URI.create(base + "/api/"), URI.create(base + "/token"));

        strava.get("athlete/activities");
        clock.now = Instant.parse("2026-10-19T08:10:00Z");
        assertThatThrownBy(() -> strava.get("athlete/activities")).isInstanceOf(StravaClient.QuotaReached.class);
        assertThat(reads).hasValue(1);

        // A new quarter hour: the old count is void, the call goes through.
        clock.now = Instant.parse("2026-10-19T08:16:00Z");
        strava.get("athlete/activities");
        assertThat(reads).hasValue(2);
    }
}
