package io.github.pharaphara.raceradio.bot.athlete;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Single-use invitations. The code is shown once, to the administrator; only its hash is kept, so
 * the database alone lets nobody in. Eight characters without look-alikes (no O, 0, I, 1), valid a week.
 */
public final class Invitations {

    static final Duration VALID = Duration.ofDays(7);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An invitation still good to use, and the model route it opens. */
    public record Invitation(String codeHash, String modelRoute) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Invitations(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A new code, returned in clear this once. */
    public String create(String modelRoute) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        Instant now = clock.instant();
        jdbc.update("INSERT INTO invitation (code_hash, model_route, created_at, expires_at) VALUES (?, ?, ?, ?)",
                hash(code.toString()), modelRoute, now.toString(), now.plus(VALID).toString());
        return code.toString();
    }

    /** The invitation behind a code typed by a newcomer, if it is unused and not expired. */
    public Optional<Invitation> valid(String typed) {
        return jdbc.query("SELECT code_hash, model_route FROM invitation WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
                (rs, i) -> new Invitation(rs.getString(1), rs.getString(2)), hash(typed), clock.instant().toString()).stream().findFirst();
    }

    /** Spends the invitation; false when someone spent it first. */
    public boolean use(Invitation invitation, AthleteId by) {
        return jdbc.update("UPDATE invitation SET used_by = ?, used_at = ? WHERE code_hash = ? AND used_at IS NULL",
                by.value(), clock.instant().toString(), invitation.codeHash()) == 1;
    }

    /** Codes are read without case, spaces or dashes: "abcd-2345" is "ABCD2345". */
    static String hash(String code) {
        String plain = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
