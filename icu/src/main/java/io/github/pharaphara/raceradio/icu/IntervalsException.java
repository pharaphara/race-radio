package io.github.pharaphara.raceradio.icu;

/**
 * A failed call to Intervals.icu, classified so that the coach can tell the athlete what happened
 * in plain words instead of relaying a status code. Nothing here is ever turned into a guess: when
 * data cannot be read, the coach says so and does not invent a review.
 */
public final class IntervalsException extends RuntimeException {

    public enum Kind {
        UNAUTHORIZED("I don't have access to your Intervals.icu account: the API key may have been regenerated."),
        NOT_FOUND("I can't find that on Intervals.icu."),
        RATE_LIMITED("Intervals.icu asked me to slow down. I'll try again in a minute."),
        NETWORK("I couldn't reach Intervals.icu."),
        REJECTED("Intervals.icu refused the request."),
        SERVER("Intervals.icu had a problem on its side.");

        private final String plainWords;

        Kind(String plainWords) {
            this.plainWords = plainWords;
        }

        public String plainWords() {
            return plainWords;
        }
    }

    private final Kind kind;
    private final int status;

    public IntervalsException(Kind kind, int status, String detail, Throwable cause) {
        super(kind + (status > 0 ? " (HTTP " + status + ")" : "") + (detail == null ? "" : ": " + detail), cause);
        this.kind = kind;
        this.status = status;
    }

    public static IntervalsException fromStatus(int status, String body) {
        Kind kind = switch (status) {
            case 401, 403 -> Kind.UNAUTHORIZED;
            case 404 -> Kind.NOT_FOUND;
            case 429 -> Kind.RATE_LIMITED;
            default -> status >= 500 ? Kind.SERVER : Kind.REJECTED;
        };
        String detail = body == null ? null : body.length() > 300 ? body.substring(0, 300) + "…" : body;
        return new IntervalsException(kind, status, detail, null);
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }
}
