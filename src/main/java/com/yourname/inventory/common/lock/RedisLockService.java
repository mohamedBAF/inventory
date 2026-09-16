package com.yourname.inventory.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The engine behind {@link DistributedLock}: a Redis lock in ~40 lines of real logic.
 *
 * ACQUIRE is one command: {@code SET key token NX PX lease}
 *   - NX: only if the key does not exist   -> mutual exclusion
 *   - PX: with a millisecond TTL           -> automatic recovery if the owner crashes
 *   Doing it as SETNX + EXPIRE (two commands) is the classic bug: crash between them and the
 *   lock never expires. {@code setIfAbsent(key, value, ttl)} maps to the single atomic form.
 *
 * RELEASE is a Lua script: compare the token, then delete - atomically. See RedisConfig.
 *
 * Honest limitations (know them before you copy this into production):
 *   - Not reentrant: the same thread calling a second @DistributedLock with the same key deadlocks
 *     against itself until the wait time expires.
 *   - No TTL watchdog: a method slower than its lease loses the lock while still running.
 *   - Single-node semantics. With Redis replication a failover can lose a just-written lock;
 *     that is what the Redlock algorithm / Redisson address.
 * For real systems use Redisson or Spring Integration's RedisLockRegistry. This class exists so
 * you can SEE what those libraries do for you.
 */
@Service
public class RedisLockService {

    static final String KEY_PREFIX = "inventory:lock:";

    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;

    public RedisLockService(StringRedisTemplate redis, RedisScript<Long> releaseLockScript) {
        this.redis = redis;
        this.releaseScript = releaseLockScript;
    }

    /** Single attempt, no waiting. Returns empty if somebody else holds the lock. */
    public Optional<LockHandle> tryAcquire(String key, Duration lease) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, token, lease);

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Lock ACQUIRED key={} token={} lease={}ms", key, token, lease.toMillis());
            return Optional.of(new LockHandle(key, token, Instant.now(), lease));
        }
        log.debug("Lock BUSY key={}", key);
        return Optional.empty();
    }

    /**
     * Retry until {@code wait} elapses.
     *
     * The sleep is randomised (50-150ms). With a fixed sleep, N waiters wake up in lockstep and
     * hammer Redis together every cycle - the "thundering herd". Jitter spreads them out.
     * Note this is a spin-wait: it burns a thread while waiting, which is exactly why the wait
     * times in this project are short. (On Java 21 virtual threads the cost is far lower.)
     */
    public Optional<LockHandle> acquire(String key, Duration wait, Duration lease) {
        long deadline = System.nanoTime() + wait.toNanos();
        int attempt = 0;

        while (true) {
            attempt++;
            Optional<LockHandle> handle = tryAcquire(key, lease);
            if (handle.isPresent()) {
                if (attempt > 1) {
                    log.debug("Lock acquired for key={} after {} attempts", key, attempt);
                }
                return handle;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(50, 150));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * Release, but only if we still own it.
     *
     * A {@code false} return is not noise - it means the lease expired while the critical section
     * was still running, so another worker may have been inside it at the same time. Log it loudly.
     */
    public boolean release(LockHandle handle) {
        Long released = redis.execute(releaseScript, List.of(handle.redisKey()), handle.token());
        boolean ok = released != null && released == 1L;

        if (!ok) {
            log.warn("Lock RELEASE failed key={} - the lease ({}ms) expired before the work finished. "
                            + "Another request may have entered the critical section.",
                    handle.key(), handle.lease().toMillis());
        } else {
            log.debug("Lock RELEASED key={} heldFor={}ms",
                    handle.key(), Duration.between(handle.acquiredAt(), Instant.now()).toMillis());
        }
        return ok;
    }

    /** Programmatic form, for when an annotation cannot express the key (see StockService#transfer). */
    public <T> T runLocked(String key, Duration wait, Duration lease, Supplier<T> work) {
        LockHandle handle = acquire(key, wait, lease)
                .orElseThrow(() -> new LockAcquisitionException(key, wait.toMillis()));
        try {
            return work.get();
        } finally {
            release(handle);
        }
    }

    /** Debug helper for GET /api/admin/locks - which locks are held right now, and for how long. */
    public List<ActiveLock> activeLocks() {
        List<ActiveLock> result = new ArrayList<>();
        // SCAN (not KEYS) - KEYS blocks the single-threaded Redis server on large keyspaces.
        redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(KEY_PREFIX + "*").count(100).build())
                .forEachRemaining(key -> {
                    Long ttl = redis.getExpire(key);
                    result.add(new ActiveLock(key.substring(KEY_PREFIX.length()),
                            redis.opsForValue().get(key), ttl == null ? -1 : ttl));
                });
        return result;
    }

    public record ActiveLock(String key, String ownerToken, long ttlSeconds) {
    }
}
