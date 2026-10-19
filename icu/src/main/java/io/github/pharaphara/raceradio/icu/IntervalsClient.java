package io.github.pharaphara.raceradio.icu;

import io.github.pharaphara.raceradio.core.series.Samples;
import io.github.pharaphara.raceradio.icu.model.ActivityInterval;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import io.github.pharaphara.raceradio.icu.model.Athlete;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.icu.model.WellnessDay;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to Intervals.icu. Writes go through {@link IntervalsWriter}, the only class allowed
 * to change anything, so that dry-run and ownership checks live in one place.
 *
 * <p>Authentication is HTTP Basic with the literal user name {@code API_KEY} and the personal key
 * as password, as the Intervals.icu API documents it.
 */
public class IntervalsClient {

    public static final URI DEFAULT_BASE = URI.create("https://intervals.icu/api/v1/");

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final IntervalsCredentials credentials;
    private final HttpClient http;
    private final URI base;
    private final String authorization;

    public IntervalsClient(IntervalsCredentials credentials) {
        this(credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), DEFAULT_BASE);
    }

    public IntervalsClient(IntervalsCredentials credentials, HttpClient http, URI base) {
        this.credentials = credentials;
        this.http = http;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(("API_KEY:" + credentials.apiKey()).getBytes(StandardCharsets.UTF_8));
    }

    public Athlete athlete() {
        return get(athletePath(""), new TypeReference<>() { });
    }

    public List<WellnessDay> wellness(LocalDate from, LocalDate to) {
        return get(athletePath("wellness?oldest=" + from + "&newest=" + to), new TypeReference<>() { });
    }

    public List<ActivitySummary> activities(LocalDate from, LocalDate to) {
        return get(athletePath("activities?oldest=" + from + "&newest=" + to), new TypeReference<>() { });
    }

    /** Activities exactly as Intervals.icu sends them, every field kept, for the local archive. */
    public List<JsonNode> activitiesRaw(LocalDate from, LocalDate to) {
        return get(athletePath("activities?oldest=" + from + "&newest=" + to), new TypeReference<>() { });
    }

    /** Wellness days exactly as Intervals.icu sends them, for the local archive. */
    public List<JsonNode> wellnessRaw(LocalDate from, LocalDate to) {
        return get(athletePath("wellness?oldest=" + from + "&newest=" + to), new TypeReference<>() { });
    }

    /** Calendar entries exactly as Intervals.icu sends them, for the local cache of what is planned. */
    public List<JsonNode> eventsRaw(LocalDate from, LocalDate to) {
        return get(athletePath("events?oldest=" + from + "&newest=" + to), new TypeReference<>() { });
    }

    /** The CSV export of an activity's streams, as sent, so it can be kept and parsed again later. */
    public String streamsCsv(String activityId) {
        return send(request("activity/" + activityId + "/streams.csv"));
    }

    public List<CalendarEvent> events(LocalDate from, LocalDate to) {
        return get(athletePath("events?oldest=" + from + "&newest=" + to), new TypeReference<>() { });
    }

    /** One activity's summary. */
    public ActivitySummary activity(String activityId) {
        return get("activity/" + activityId, new TypeReference<>() { });
    }

    /** Intervals.icu's own interval detection for an activity, WORK and RECOVERY alike. */
    public List<ActivityInterval> intervals(String activityId) {
        JsonNode root = get("activity/" + activityId + "/intervals", new TypeReference<>() { });
        JsonNode list = root.path("icu_intervals");
        return list.isMissingNode() || list.isNull() ? List.of()
                : JSON.convertValue(list, new TypeReference<List<ActivityInterval>>() { });
    }

    /**
     * Every stream of an activity, read from the CSV export rather than the JSON one. The CSV
     * carries latitude and longitude as two plain columns; the JSON {@code latlng} stream keeps
     * latitudes in {@code data} and longitudes in a second array, {@code data2}, which is easy to miss.
     */
    public ActivityStreams streams(String activityId) {
        return parseStreamsCsv(send(request("activity/" + activityId + "/streams.csv")));
    }

    /** Parses a streams CSV, fetched now or kept from an earlier fetch. */
    public static ActivityStreams parseStreamsCsv(String csv) {
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return new ActivityStreams(Map.of());
        }
        // The export starts with a UTF-8 byte order mark; left in, the first column is "﻿time".
        String[] header = lines.getFirst().replace("﻿", "").split(",", -1);
        List<double[]> columns = new ArrayList<>();
        for (int c = 0; c < header.length; c++) {
            columns.add(new double[lines.size() - 1]);
        }
        for (int r = 1; r < lines.size(); r++) {
            String[] cells = lines.get(r).split(",", -1);
            for (int c = 0; c < header.length; c++) {
                columns.get(c)[r - 1] = c < cells.length ? parseCell(cells[c]) : Double.NaN;
            }
        }
        Map<String, Samples> out = new LinkedHashMap<>();
        for (int c = 0; c < header.length; c++) {
            out.put(header[c].strip(), Samples.of(columns.get(c)));
        }
        return new ActivityStreams(out);
    }

    private static double parseCell(String cell) {
        String s = cell.strip();
        if (s.isEmpty() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    IntervalsCredentials credentials() {
        return credentials;
    }

    String athletePath(String rest) {
        return "athlete/" + credentials.athleteId() + (rest.isEmpty() || rest.startsWith("?") ? rest : "/" + rest);
    }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Accept", "application/json");
    }

    <T> T get(String path, TypeReference<T> type) {
        return JSON.readValue(send(request(path)), type);
    }

    String send(HttpRequest.Builder request) {
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IntervalsException(IntervalsException.Kind.NETWORK, 0, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntervalsException(IntervalsException.Kind.NETWORK, 0, "interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw IntervalsException.fromStatus(response.statusCode(), response.body());
        }
        return response.body();
    }

    static ObjectMapper json() {
        return JSON;
    }
}
