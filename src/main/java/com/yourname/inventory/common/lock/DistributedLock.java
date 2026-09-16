package com.yourname.inventory.common.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * LESSON 3 - a distributed lock as an annotation.
 *
 * {@code synchronized} and {@code ReentrantLock} only protect one JVM. The moment you run two
 * instances of this app behind a load balancer, two requests can read "stock = 1" at the same
 * time and both sell it. A lock that lives in Redis is shared by every instance.
 *
 * Usage:
 * <pre>
 * &#64;DistributedLock(key = "'stock:' + #productId", waitTimeMs = 3000, leaseTimeMs = 5000)
 * public StockOperationResponse reserve(UUID productId, int quantity) { ... }
 * </pre>
 *
 * The {@code key} is a SpEL expression evaluated against the method arguments, so the lock is
 * per-product, not global. Locking the whole endpoint would be correct but would serialise every
 * customer in the shop - correctness is easy, GRANULARITY is the skill.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * SpEL expression producing the lock name. Method arguments are available by name
     * ({@code #productId}) and as {@code #p0}, {@code #a0}.
     * Remember to quote literals: {@code "'stock:' + #productId"}.
     */
    String key();

    /**
     * How long to keep trying before giving up.
     *
     * 0 = fail immediately if the lock is taken (good for "cancel if already running" jobs).
     * Too high and your threads pile up waiting; too low and legitimate traffic gets 409s.
     */
    long waitTimeMs() default 3_000;

    /**
     * How long the lock survives in Redis if this JVM dies mid-method (the key's TTL).
     *
     * This is the dangerous one. Too short: the lock expires while you are still working and a
     * second worker enters the critical section. Too long: a crash blocks that product until the
     * TTL runs out. Rule of thumb: several times the p99 of the method, and keep the method short.
     * (Production libraries such as Redisson solve this with a "watchdog" that extends the TTL
     * while the thread is alive - deliberately not implemented here, so the trade-off stays visible.)
     */
    long leaseTimeMs() default 10_000;

    /**
     * true  -> throw {@link LockAcquisitionException} (mapped to HTTP 409) when the lock is busy.
     * false -> skip the method silently and return null. Only safe for void/idempotent jobs.
     */
    boolean throwOnFailure() default true;
}
