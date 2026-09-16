package com.yourname.inventory.common.retry;

import org.springframework.stereotype.Component;

/**
 * Counts how many times a retried method actually ran, so the HTTP response can show it.
 *
 * A {@link ThreadLocal} works for exactly one reason worth remembering: Spring's retry
 * interceptor re-invokes the method on the SAME thread (it is a loop with sleeps, not a
 * thread hand-off). That also means a retry blocks its request thread for the whole backoff -
 * budget {@code maxRetries * maxDelay} against your thread pool before you tune those numbers up.
 *
 * Pure teaching instrumentation - in production you would read this from Micrometer instead.
 */
@Component
public class RetryAttemptTracker {

    private final ThreadLocal<Integer> attempts = ThreadLocal.withInitial(() -> 0);

    /** Call once per request, BEFORE entering the retryable method. */
    public void reset() {
        attempts.set(0);
    }

    /** Call as the first line of the retryable method. Returns the attempt number (1-based). */
    public int next() {
        int value = attempts.get() + 1;
        attempts.set(value);
        return value;
    }

    public int current() {
        return attempts.get();
    }
}
