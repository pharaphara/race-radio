package io.github.pharaphara.raceradio.bot.llm;

import io.github.pharaphara.raceradio.bot.store.Ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * The single way the coach talks to the language model. Every call is checked against the monthly
 * budget, then priced in the ledger and written to the journal, whatever its outcome.
 *
 * <p>The model only ever receives numbers that were already computed, in fenced data blocks, and
 * writes prose about them. When it cannot (budget spent, a refusal, an outage) the caller uses its
 * deterministic text instead: the service keeps working, it just stops writing nicely.
 */
public final class CoachModel {

    /** The month's budget is spent; the caller degrades to deterministic text. */
    public static final class BudgetExhausted extends RuntimeException {
        BudgetExhausted(BigDecimal spent, BigDecimal budget) {
            super("monthly model budget spent: " + spent + " of " + budget + " dollars");
        }
    }

    private final ModelTransport transport;
    private final SystemPrompt system;
    private final Ledger ledger;
    private final Journal journal;
    private final Pricing pricing;
    private final String model;
    private final boolean fallbacks;
    private final BigDecimal budget;
    private final Clock clock;
    private final ZoneId zone;

    public CoachModel(ModelTransport transport, SystemPrompt system, Ledger ledger, Journal journal, Pricing pricing,
                      String model, boolean fallbacks, BigDecimal budget, Clock clock, ZoneId zone) {
        this.transport = transport;
        this.system = system;
        this.ledger = ledger;
        this.journal = journal;
        this.pricing = pricing;
        this.model = model;
        this.fallbacks = fallbacks;
        this.budget = budget;
        this.clock = clock;
        this.zone = zone;
    }

    public String billing() {
        return transport.billing();
    }

    public String promptVersion() {
        return system.version();
    }

    public BigDecimal spentThisMonth() {
        return ledger.costOf(YearMonth.now(clock.withZone(zone)), zone);
    }

    public BigDecimal budget() {
        return budget;
    }

    /**
     * @param effort     low for short wording, medium for analysis
     * @param maxTokens  generous: a cut-off answer would need a retry
     */
    public ModelReply ask(Purpose purpose, String effort, long maxTokens, String userContent) {
        return call(new ModelRequest(purpose, model, effort, maxTokens, fallbacks, system.version(), userContent), null, null);
    }

    /** One image with the request. The image itself is not journaled, only its hash. */
    public ModelReply askWithImage(Purpose purpose, String effort, long maxTokens, String userContent, byte[] image, String mediaType) {
        String sha = SystemPrompt.sha256(new String(java.util.Base64.getEncoder().encode(image), java.nio.charset.StandardCharsets.US_ASCII));
        return call(new ModelRequest(purpose, model, effort, maxTokens, fallbacks, system.version(), userContent, sha), image, mediaType);
    }

    private ModelReply call(ModelRequest request, byte[] image, String mediaType) {
        BigDecimal spent = spentThisMonth();
        if (spent.compareTo(budget) >= 0) {
            throw new BudgetExhausted(spent, budget);
        }
        Instant at = clock.instant();
        ModelReply reply;
        try {
            reply = image == null ? transport.send(request, system) : transport.sendWithImage(request, system, image, mediaType);
        } catch (RuntimeException e) {
            ModelReply failed = new ModelReply("", null, "error", false, Usage.NONE, 0, null);
            String ref = journal.write(at, request, system, failed, e.getClass().getSimpleName() + ": " + e.getMessage());
            ledger.record(at, request, failed, BigDecimal.ZERO, ref, transport.billing());
            throw e;
        }
        String answering = reply.answeringModel() == null ? model : reply.answeringModel();
        BigDecimal cost = reply.reportedCostUsd() != null ? BigDecimal.valueOf(reply.reportedCostUsd())
                : pricing.cost(answering, reply.usage());
        String ref = journal.write(at, request, system, reply, null);
        ledger.record(at, request, reply, cost, ref, transport.billing());
        return reply;
    }
}
