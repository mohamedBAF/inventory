package com.yourname.inventory.common.lock;

import java.time.Duration;
import java.time.Instant;

/**
 * Proof of ownership of a lock.
 *
 * The {@code token} is the whole point: it is a random value that only the acquiring thread
 * knows. Releasing compares the stored value with this token, so you can never delete a lock
 * that has expired and been re-acquired by somebody else.
 */
public record LockHandle(String key, String token, Instant acquiredAt, Duration lease) {

    public String redisKey() {
        return RedisLockService.KEY_PREFIX + key;
    }
}
