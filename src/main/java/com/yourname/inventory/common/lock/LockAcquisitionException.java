package com.yourname.inventory.common.lock;

/** Thrown when a {@link DistributedLock} could not be acquired within its wait time. */
public class LockAcquisitionException extends RuntimeException {

    private final String lockKey;

    public LockAcquisitionException(String lockKey, long waitedMs) {
        super("Could not acquire lock '" + lockKey + "' after " + waitedMs + "ms - "
                + "another request is already working on this resource");
        this.lockKey = lockKey;
    }

    public String getLockKey() {
        return lockKey;
    }
}
