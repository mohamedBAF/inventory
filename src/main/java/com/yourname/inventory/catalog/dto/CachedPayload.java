package com.yourname.inventory.catalog.dto;

/**
 * Wraps a value with where it came from.
 *
 * With @Cacheable you cannot tell a hit from a miss by looking at the response - the method
 * simply did not run. Manual cache-aside code can report it, which makes the cache observable.
 */
public record CachedPayload<T>(T value, boolean cacheHit, long elapsedMs, String cacheKey, Long ttlSeconds) {
}
