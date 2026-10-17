package io.github.pharaphara.raceradio.bot.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The coach's standing instructions: the voice and rules, then the knowledge base. It is stable
 * by design, so that it can be cached; its version is a hash of its content, recorded with every
 * call and every decision, so an answer from January can be read against the rules that produced it.
 */
public final class SystemPrompt {

    private final String text;
    private final String version;

    private SystemPrompt(String text) {
        this.text = text;
        this.version = sha256(text).substring(0, 12);
    }

    public static SystemPrompt of(String text) {
        return new SystemPrompt(text);
    }

    /** Concatenates classpath resources in the given order, separated by a blank line. */
    public static SystemPrompt fromClasspath(List<String> resources) {
        StringBuilder b = new StringBuilder();
        for (String r : resources) {
            try (InputStream in = SystemPrompt.class.getClassLoader().getResourceAsStream(r)) {
                if (in == null) {
                    throw new IllegalStateException("missing prompt resource " + r);
                }
                if (!b.isEmpty()) {
                    b.append("\n\n");
                }
                b.append(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new SystemPrompt(b.toString());
    }

    public String text() {
        return text;
    }

    public String version() {
        return version;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
