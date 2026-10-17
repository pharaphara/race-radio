package io.github.pharaphara.raceradio.icu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Athlete id and personal API key. The key never appears in {@link #toString()}, so a credential
 * object that ends up in a log line or an exception message does not leak it.
 */
public record IntervalsCredentials(String athleteId, String apiKey) {

    public IntervalsCredentials {
        Objects.requireNonNull(athleteId, "athleteId");
        Objects.requireNonNull(apiKey, "apiKey");
        if (!athleteId.matches("i\\d+")) {
            throw new IllegalArgumentException("an Intervals.icu athlete id is the letter i followed by digits");
        }
    }

    /** Reads {@code INTERVALS_ATHLETE_ID} and {@code INTERVALS_API_KEY} from an environment map. */
    public static IntervalsCredentials from(Map<String, String> env) {
        String id = env.get("INTERVALS_ATHLETE_ID");
        String key = env.get("INTERVALS_API_KEY");
        if (id == null || key == null) {
            throw new IllegalStateException("INTERVALS_ATHLETE_ID and INTERVALS_API_KEY must both be set");
        }
        return new IntervalsCredentials(id.strip(), key.strip());
    }

    /** Reads a {@code KEY=value} file such as {@code .env}, ignoring blank lines and comments. */
    public static Map<String, String> readDotEnv(Path file) throws IOException {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String l = line.strip();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq < 1) {
                continue;
            }
            env.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip().replace("\"", ""));
        }
        return env;
    }

    @Override
    public String toString() {
        return "IntervalsCredentials[athleteId=" + athleteId + ", apiKey=***]";
    }
}
