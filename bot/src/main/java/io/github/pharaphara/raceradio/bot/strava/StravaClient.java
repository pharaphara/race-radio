package io.github.pharaphara.raceradio.bot.strava;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reads from the Strava API on behalf of the one athlete who authorised the app.
 *
 * <p>The access token lasts six hours and is renewed from the refresh token. Strava may hand back a
 * new refresh token when it does; the newest one is kept in the database, so the one in {@code .env}
 * only has to be right once. Every answer carries the read quota already used; the client refuses
 * to go on once it is near the limit ({@link QuotaReached}), rather than meeting a 429.
 */
public class StravaClient {

    static final URI API = URI.create("https://www.strava.com/api/v3/");
    static final URI TOKEN = URI.create("https://www.strava.com/oauth/token");
    /** Reads kept in reserve below each limit, for anything else that might read in the meantime. */
    static final int RESERVE_15_MIN = 10;
    static final int RESERVE_DAY = 50;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The read quota is used up for now; try again after {@link #retryAfter()}. */
    public static final class QuotaReached extends RuntimeException {
        private final Instant retryAfter;

        QuotaReached(Instant retryAfter) {
            super("Strava read quota reached, retry after " + retryAfter);
            this.retryAfter = retryAfter;
        }

        public Instant retryAfter() {
            return retryAfter;
        }
    }

    private final String clientId;
    private final String clientSecret;
    private final String envRefreshToken;
    private final JdbcTemplate jdbc;
    private final HttpClient http;
    private final Clock clock;
    private String accessToken;
    private Instant accessExpires = Instant.EPOCH;
    private int[] usage = {0, 0};
    private int[] limit = {100, 1000};

    public StravaClient(String clientId, String clientSecret, String refreshToken, JdbcTemplate jdbc, Clock clock) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.envRefreshToken = refreshToken;
        this.jdbc = jdbc;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** True when the app's id, secret and a refresh token are all set. */
    public boolean configured() {
        return !blank(clientId) && !blank(clientSecret) && !blank(refreshToken());
    }

    /** GET on the API. Refuses when the read quota is nearly used up. */
    public synchronized JsonNode get(String path) {
        guard();
        HttpRequest req = HttpRequest.newBuilder(API.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + access()).header("User-Agent", "race-radio").GET().build();
        HttpResponse<String> r = send(req);
        r.headers().firstValue("x-readratelimit-usage").ifPresent(v -> usage = pair(v, usage));
        r.headers().firstValue("x-readratelimit-limit").ifPresent(v -> limit = pair(v, limit));
        if (r.statusCode() == 429) {
            throw new QuotaReached(nextWindow());
        }
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("Strava " + r.statusCode() + " on " + path + ": " + r.body().substring(0, Math.min(200, r.body().length())));
        }
        return JSON.readTree(r.body());
    }

    /** Reads used in the current quarter hour and today, as Strava last reported them. */
    public int[] usage() {
        return usage.clone();
    }

    private void guard() {
        if (usage[1] >= limit[1] - RESERVE_DAY) {
            throw new QuotaReached(nextUtcMidnight());
        }
        if (usage[0] >= limit[0] - RESERVE_15_MIN) {
            throw new QuotaReached(nextWindow());
        }
    }

    /** Strava's 15-minute windows start on the quarter hour; the daily one at midnight UTC. */
    Instant nextWindow() {
        long s = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond((s / 900 + 1) * 900);
    }

    private Instant nextUtcMidnight() {
        long s = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond((s / 86_400 + 1) * 86_400);
    }

    private String access() {
        if (accessToken != null && clock.instant().isBefore(accessExpires.minusSeconds(300))) {
            return accessToken;
        }
        String body = form("client_id", clientId, "client_secret", clientSecret,
                "grant_type", "refresh_token", "refresh_token", refreshToken());
        HttpResponse<String> r = send(HttpRequest.newBuilder(TOKEN).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "race-radio")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("Strava refused to renew the token (" + r.statusCode()
                    + "): the authorisation may have been revoked, run it again");
        }
        JsonNode t = JSON.readTree(r.body());
        accessToken = t.path("access_token").asString();
        accessExpires = Instant.ofEpochSecond(t.path("expires_at").asLong());
        String rotated = t.path("refresh_token").asString();
        if (!blank(rotated) && !rotated.equals(refreshToken())) {
            jdbc.update("INSERT INTO setting (key, value) VALUES ('strava.refresh_token', ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", rotated);
        }
        return accessToken;
    }

    private String refreshToken() {
        List<String> kept = jdbc.queryForList("SELECT value FROM setting WHERE key = 'strava.refresh_token'", String.class);
        return kept.isEmpty() ? envRefreshToken : kept.getFirst();
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Strava unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static int[] pair(String header, int[] fallback) {
        try {
            String[] p = header.split(",");
            return new int[] {Integer.parseInt(p[0].strip()), Integer.parseInt(p[1].strip())};
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (!b.isEmpty()) {
                b.append('&');
            }
            b.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1] == null ? "" : kv[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
