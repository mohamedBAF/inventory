package com.yourname.inventory.supplier;

import com.yourname.inventory.common.retry.RetryAttemptTracker;
import com.yourname.inventory.supplier.dto.SupplierQuote;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LESSON 4 - @Retryable against a flaky remote call.
 *
 * A stand-in for "the supplier's REST API": it fails a configurable percentage of the time, the
 * way a real network does. Everything you learn here applies unchanged to a RestClient call.
 *
 * WHAT DESERVES A RETRY
 *   yes - connection reset, read timeout, HTTP 503/429, deadlock, optimistic lock conflict
 *   no  - HTTP 400/404/422, validation errors, authentication failures. They will fail the same
 *         way every time; retrying only adds latency.
 *   careful - timeouts on a NON-IDEMPOTENT call (POST /payments). The first attempt may have
 *         succeeded and only the response was lost. Retry those only with an idempotency key.
 */
@Component
@RequiredArgsConstructor
public class SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(SupplierGateway.class);

    private final RetryAttemptTracker attemptTracker;

    /**
     * Backoff: 200ms, 400ms, 800ms (+/- 50ms of jitter), then give up.
     *
     * Why exponential? A fixed 200ms delay hammers a struggling service at a constant rate; each
     * doubling gives it room to recover.
     * Why jitter? Because every client that failed at the same instant would otherwise retry at
     * the same instant, and retry storms are how a brief blip becomes an outage.
     *
     * After the last failure the exception propagates unchanged - there is no @Recover in
     * Spring Framework 7. {@link RestockService} shows how to add a fallback yourself.
     */
    @Retryable(
            includes = SupplierUnavailableException.class,
            maxRetries = 3,
            delay = 200,
            multiplier = 2.0,
            jitter = 50,
            maxDelay = 2_000)
    public SupplierQuote fetchQuote(String sku, int failurePercent) {
        int attempt = attemptTracker.next();
        log.info("Calling supplier API for sku={} (attempt #{})", sku, attempt);

        if (ThreadLocalRandom.current().nextInt(100) < failurePercent) {
            log.warn("Supplier API failed for sku={} on attempt #{}", sku, attempt);
            throw new SupplierUnavailableException(
                    "Supplier timed out for sku " + sku + " (attempt " + attempt + ")");
        }

        log.info("Supplier API answered for sku={} after {} attempt(s)", sku, attempt);
        return new SupplierQuote(sku,
                BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(5, 50))
                        .setScale(2, RoundingMode.HALF_UP),
                ThreadLocalRandom.current().nextInt(1, 15),
                "ACME Supplies",
                attempt,
                LocalDateTime.now());
    }

    /**
     * Same call with an overall TIME BUDGET instead of an attempt budget.
     *
     * {@code timeout = 2000} stops starting new attempts once 2 seconds have passed, however
     * many retries are left. Use this when the caller has an SLA: three retries with growing
     * backoff can easily add up to more time than the user is willing to wait.
     */
    @Retryable(
            includes = SupplierUnavailableException.class,
            maxRetries = 10,
            delay = 150,
            multiplier = 1.5,
            timeout = 2_000)
    public SupplierQuote fetchQuoteWithinBudget(String sku, int failurePercent) {
        return fetchQuoteInternal(sku, failurePercent);
    }

    private SupplierQuote fetchQuoteInternal(String sku, int failurePercent) {
        int attempt = attemptTracker.next();
        if (ThreadLocalRandom.current().nextInt(100) < failurePercent) {
            throw new SupplierUnavailableException("Supplier timed out for sku " + sku);
        }
        return new SupplierQuote(sku, BigDecimal.valueOf(19.99), 3, "ACME Supplies",
                attempt, LocalDateTime.now());
    }
}
