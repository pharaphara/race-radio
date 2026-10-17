package io.github.pharaphara.raceradio.bot.telegram;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few Bot API methods the coach needs, on the JDK's HTTP client. A library would bring far
 * more than four methods and one more thing to break while nobody is watching.
 *
 * <p>The bot token is part of every URL, so no URL ever appears in an exception or a log line:
 * errors carry the method name and Telegram's own description only.
 */
public class TelegramClient {

    /** Telegram caps a message at 4096 characters. */
    public static final int MAX_MESSAGE = 4096;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String base;

    public TelegramClient(String token) {
        this(token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), URI.create("https://api.telegram.org/"));
    }

    public TelegramClient(String token, HttpClient http, URI api) {
        this.http = http;
        this.base = api + "bot" + token + "/";
    }

    /** An incoming message or button press, reduced to what the coach uses. */
    public record Update(long updateId, long chatId, String text, String callbackId, String callbackData) {
        public boolean isCallback() {
            return callbackId != null;
        }
    }

    /** Long polling: waits up to {@code waitSeconds} for new updates after {@code offset}. */
    public List<Update> updates(long offset, int waitSeconds) {
        JsonNode r = call("getUpdates", Map.of("offset", offset, "timeout", waitSeconds,
                "allowed_updates", List.of("message", "callback_query")), Duration.ofSeconds(waitSeconds + 15));
        List<Update> out = new ArrayList<>();
        for (JsonNode u : r.path("result")) {
            long id = u.path("update_id").asLong();
            JsonNode msg = u.path("message");
            JsonNode cb = u.path("callback_query");
            if (msg.isObject()) {
                out.add(new Update(id, msg.path("chat").path("id").asLong(), msg.path("text").asString(null), null, null));
            } else if (cb.isObject()) {
                out.add(new Update(id, cb.path("message").path("chat").path("id").asLong(), null,
                        cb.path("id").asString(null), cb.path("data").asString(null)));
            } else {
                out.add(new Update(id, 0, null, null, null));
            }
        }
        return out;
    }

    /** Sends plain text, split on line boundaries if it is longer than Telegram allows. */
    public void send(long chatId, String text) {
        for (String part : split(text)) {
            call("sendMessage", Map.of("chat_id", chatId, "text", part), Duration.ofSeconds(20));
        }
    }

    /** Sends text with one row of buttons; each button returns its {@code data} when pressed. */
    public void sendWithButtons(long chatId, String text, Map<String, String> labelToData) {
        List<Map<String, String>> row = new ArrayList<>();
        labelToData.forEach((label, data) -> row.add(Map.of("text", label, "callback_data", data)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) : text);
        body.put("reply_markup", Map.of("inline_keyboard", List.of(row)));
        call("sendMessage", body, Duration.ofSeconds(20));
    }

    /** Sends a PNG image, with a short caption. */
    public void sendPhoto(long chatId, byte[] png, String caption) {
        String boundary = "race-radio-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try {
            field(body, boundary, "chat_id", String.valueOf(chatId));
            if (caption != null && !caption.isBlank()) {
                field(body, boundary, "caption", caption.length() > 1024 ? caption.substring(0, 1024) : caption);
            }
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"chart.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(png);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "sendPhoto"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        check("sendPhoto", req);
    }

    private static void field(java.io.ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    public void acknowledge(String callbackId, String note) {
        call("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", note), Duration.ofSeconds(10));
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (current.length() + line.length() + 1 > MAX_MESSAGE && !current.isEmpty()) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (line.length() > MAX_MESSAGE) {
                for (int i = 0; i < line.length(); i += MAX_MESSAGE) {
                    parts.add(line.substring(i, Math.min(line.length(), i + MAX_MESSAGE)));
                }
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private JsonNode call(String method, Object body, Duration timeout) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return check(method, req);
    }

    private JsonNode check(String method, HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Telegram " + method + " unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram " + method + " interrupted");
        }
        JsonNode r = JSON.readTree(res.body());
        if (!r.path("ok").asBoolean(false)) {
            throw new IllegalStateException("Telegram " + method + " failed: " + r.path("description").asString("HTTP " + res.statusCode()));
        }
        return r;
    }
}
