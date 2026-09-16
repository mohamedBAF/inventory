package com.yourname.inventory.stock;

import com.yourname.inventory.common.lock.DistributedLock;
import com.yourname.inventory.common.lock.RedisLockService;
import com.yourname.inventory.common.retry.RetryAttemptTracker;
import com.yourname.inventory.stock.dto.StockOperationResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The RESILIENCE half of the stock module: locks and retries, no SQL.
 *
 * Read this class next to {@link StockService} - the split exists because Spring AOP proxies only
 * intercept calls that arrive from OUTSIDE the bean. An @DistributedLock or @Retryable method
 * invoked as {@code this.method()} runs with no lock and no retry, silently. Two beans, no bugs.
 */
@Service
@RequiredArgsConstructor
public class StockFacade {

    private static final Logger log = LoggerFactory.getLogger(StockFacade.class);

    private final StockService stockService;
    private final RedisLockService lockService;
    private final RetryAttemptTracker attemptTracker;

    /**
     * LOCK EXAMPLE 1 - the annotation, one lock per product.
     *
     * key = "'stock:' + #productId" -> two customers buying DIFFERENT products never wait for
     * each other; two customers buying the SAME product are serialised. That granularity is the
     * difference between a system that scales and a system with one global mutex.
     *
     * waitTime 3s: a customer will queue briefly rather than see an error.
     * leaseTime 5s: if this pod dies mid-reservation, the product is stuck for 5s, no longer.
     *
     * Note the lock is taken BEFORE @Transactional on the delegate begins and released AFTER it
     * commits (see DistributedLockAspect's @Order) - releasing before commit would let the next
     * waiter read stale, uncommitted data.
     */
    @DistributedLock(key = "'stock:' + #productId", waitTimeMs = 3_000, leaseTimeMs = 5_000)
    public StockOperationResponse reserveWithDistributedLock(UUID productId, int quantity, String orderRef) {
        log.info("Entered critical section for product {} (order {})", productId, orderRef);
        return stockService.reserveUnsafe(productId, quantity, orderRef)
                .withNote("protected by Redis lock 'stock:" + productId + "'");
    }

    /**
     * LOCK EXAMPLE 2 - fail fast instead of queueing.
     *
     * waitTime 0 + throwOnFailure -> the second concurrent request gets 409 immediately.
     * This is the right shape for "start the nightly re-index" style endpoints, where waiting is
     * pointless because the work is already being done.
     */
    @DistributedLock(key = "'stock-audit:' + #productId", waitTimeMs = 0, leaseTimeMs = 30_000)
    public String runExclusiveAudit(UUID productId) {
        log.info("Audit started for {}", productId);
        try {
            Thread.sleep(3_000);   // pretend this is a long job
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return "Audit finished for product " + productId;
    }

    /**
     * RETRY EXAMPLE 1 - optimistic locking + retry, the pairing you will use most often.
     *
     * The delegate has no lock: it reads, computes, writes, and lets @Version detect a conflict.
     * Under contention the loser throws ObjectOptimisticLockingFailureException (a subclass of
     * OptimisticLockingFailureException) and this annotation simply runs it again on fresh data.
     *
     * Reading the attributes:
     *   includes    - retry ONLY these. Never retry blindly: replaying a non-idempotent call, or
     *                 a validation error that will fail identically forever, is worse than failing.
     *   maxRetries  - 4 retries = up to 5 invocations (spring-retry's maxAttempts counted totals).
     *   delay 50 / multiplier 2.0 -> 50, 100, 200, 400ms exponential backoff.
     *   jitter 25   - randomises each delay by +/-25ms so N conflicting writers do not retry in
     *                 lockstep and collide again (the retry equivalent of a thundering herd).
     *   maxDelay    - caps the exponential growth.
     *
     * There is no @Recover in Spring Framework 7: if all attempts fail the last exception
     * propagates (mapped to HTTP 409 by GlobalExceptionHandler). When you need a fallback value,
     * write it by hand - see RestockService.
     */
    @Retryable(
            includes = {OptimisticLockingFailureException.class, CannotAcquireLockException.class},
            maxRetries = 4,
            delay = 50,
            multiplier = 2.0,
            jitter = 25,
            maxDelay = 500)
    public StockOperationResponse adjustStock(UUID productId, int delta, String reason) {
        int attempt = attemptTracker.next();
        if (attempt > 1) {
            log.warn("Version conflict on product {} - retry attempt #{}", productId, attempt);
        }
        return stockService.applyDeltaOptimistic(productId, delta, reason).withAttempts(attempt);
    }

    /**
     * RETRY EXAMPLE 2 - retrying a pessimistic lock timeout.
     *
     * "SELECT ... FOR UPDATE" can time out when the row is hot (CannotAcquireLockException).
     * That is a transient failure, so a couple of retries turn a 500 into a slightly slower 200.
     * A bounded timeout plus a bounded retry beats an unbounded wait.
     */
    @Retryable(includes = CannotAcquireLockException.class, maxRetries = 2, delay = 100, multiplier = 2.0)
    public StockOperationResponse reserveWithRowLock(UUID productId, int quantity, String orderRef) {
        int attempt = attemptTracker.next();
        return stockService.reserveWithRowLock(productId, quantity, orderRef).withAttempts(attempt);
    }

    /**
     * LOCK EXAMPLE 3 - programmatic locking, when a SpEL key is not expressive enough.
     *
     * Here the key must be built from two arguments sorted into a stable order, so that a
     * transfer A->B and a simultaneous transfer B->A compete for the SAME lock instead of
     * deadlocking on two different ones. Annotations are for the simple 90%; keep the
     * programmatic API for the rest.
     */
    public List<StockOperationResponse> transfer(UUID fromId, UUID toId, int quantity) {
        String lockKey = "stock-transfer:" + (fromId.compareTo(toId) < 0
                ? fromId + ":" + toId
                : toId + ":" + fromId);

        return lockService.runLocked(lockKey, Duration.ofSeconds(5), Duration.ofSeconds(10),
                () -> stockService.transfer(fromId, toId, quantity));
    }

    /**
     * BONUS (Spring Framework 7) - @ConcurrencyLimit.
     *
     * A lock says "one at a time, ever". A concurrency limit says "at most N at a time" - a
     * bulkhead. Use it to stop one expensive endpoint from consuming the whole thread pool.
     * Callers beyond the limit block until a slot frees up.
     */
    @ConcurrencyLimit(2)
    public String expensiveRecount(UUID productId) {
        try {
            Thread.sleep(1_500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return "Recounted " + productId + " (at most 2 of these run at once)";
    }
}
