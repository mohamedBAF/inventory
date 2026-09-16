package com.yourname.inventory.admin;

import com.yourname.inventory.common.lock.RedisLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * X-ray view of Redis. Not something you expose publicly (it leaks keys and lets anyone flush a
 * cache), but exactly what you want while learning: every other endpoint in this project becomes
 * observable through this one.
 *
 * Try this loop:
 *   1. GET  /api/admin/cache                        - nothing cached yet
 *   2. GET  /api/products/cached                    - populate
 *   3. GET  /api/admin/cache/products/keys          - see the key that was written
 *   4. DELETE /api/admin/cache/products             - flush
 *   5. GET  /api/products/cached                    - slow again
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCacheController {

    /** Must match CacheConfig.KEY_PREFIX, otherwise the key listing below finds nothing. */
    private static final String KEY_PREFIX = "inventory:cache:";

    private final CacheManager cacheManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisLockService lockService;

    /** Every cache Spring knows about, with a live key count from Redis. */
    @GetMapping("/cache")
    public List<Map<String, Object>> caches() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            out.add(Map.of(
                    "name", name,
                    "keys", scanKeys(name).size(),
                    "redisPattern", KEY_PREFIX + name + "::*"));
        }
        return out;
    }

    /**
     * SCAN, never KEYS. KEYS blocks the single Redis thread for the whole sweep; on a production
     * keyspace that is a self-inflicted outage. SCAN is cursor based and interleaves with traffic
     * (the price is that it may return duplicates and misses concurrent writes - fine here).
     */
    @GetMapping("/cache/{name}/keys")
    public Map<String, Object> keys(@PathVariable String name) {
        List<String> keys = scanKeys(name);
        return Map.of("cache", name, "count", keys.size(), "keys", keys);
    }

    /** Raw cached value plus its remaining TTL - the fastest way to see what a serializer produced. */
    @GetMapping("/cache/{name}/entry")
    public Map<String, Object> entry(@PathVariable String name, @RequestParam String key) {
        String redisKey = KEY_PREFIX + name + "::" + key;
        Object value = redisTemplate.opsForValue().get(redisKey);
        Long ttl = redisTemplate.getExpire(redisKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("redisKey", redisKey);
        body.put("found", value != null);
        body.put("ttlSeconds", ttl);
        body.put("value", value);
        return body;
    }

    /** Equivalent to @CacheEvict(allEntries = true) but on demand. */
    @DeleteMapping("/cache/{name}")
    public Map<String, Object> clear(@PathVariable String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            return Map.of("cache", name, "cleared", false, "reason", "no such cache");
        }
        cache.clear();
        return Map.of("cache", name, "cleared", true);
    }

    /** Evict one entry, the way @CacheEvict(key = "...") does. */
    @DeleteMapping("/cache/{name}/entry")
    public Map<String, Object> evict(@PathVariable String name, @RequestParam String key) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            return Map.of("cache", name, "evicted", false, "reason", "no such cache");
        }
        cache.evict(key);
        return Map.of("cache", name, "key", key, "evicted", true);
    }

    /**
     * Distributed locks currently held, with their remaining lease.
     *
     * Hit POST /api/stock/{id}/audit (30s lease) in one terminal and poll this in another: you see
     * the lock appear, its TTL count down, then disappear. That TTL is the safety net - if the JVM
     * holding the lock dies, Redis expires the key and the system recovers by itself.
     */
    @GetMapping("/locks")
    public List<RedisLockService.ActiveLock> locks() {
        return lockService.activeLocks();
    }

    private List<String> scanKeys(String cacheName) {
        String pattern = KEY_PREFIX + cacheName + "::*";
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }
}
