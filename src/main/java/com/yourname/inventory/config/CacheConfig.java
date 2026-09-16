package com.yourname.inventory.config;

import com.yourname.inventory.common.cache.CacheNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LESSON 2 - declarative caching with Redis.
 *
 * {@code @EnableCaching} switches on the proxy that implements @Cacheable / @CachePut /
 * @CacheEvict. Without it the annotations are inert comments.
 *
 * Three things every production Redis cache needs, all wired here:
 *  1. A TTL PER CACHE. One global TTL is always wrong: a product changes rarely, a stock level
 *     changes every second. An entry with no TTL lives forever and is the #1 cause of stale data.
 *  2. A KEY PREFIX. Redis is one flat keyspace; a prefix keeps caches inspectable and lets you
 *     wipe one app with a single pattern.
 *  3. A {@link CacheErrorHandler}. By default, if Redis is down, EVERY annotated method throws.
 *     A cache is an optimisation - losing it should degrade latency, not availability.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Everything this app writes lives under this prefix -> easy to find, easy to flush. */
    public static final String KEY_PREFIX = "inventory:cache:";

    private final GenericJacksonJsonRedisSerializer jsonSerializer;

    public CacheConfig(GenericJacksonJsonRedisSerializer jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = defaults();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        // Rarely changes, and every write path evicts it explicitly -> can afford a long TTL.
        perCache.put(CacheNames.PRODUCT, base.entryTtl(Duration.ofMinutes(10)));
        // A page is invalidated by ANY create/delete, so keep it short as a safety net.
        perCache.put(CacheNames.PRODUCT_PAGE, base.entryTtl(Duration.ofMinutes(1)));
        perCache.put(CacheNames.PRODUCT_SEARCH, base.entryTtl(Duration.ofMinutes(2)));
        // Stock is money: 30s of staleness is the most we accept, and we refuse to cache nulls.
        perCache.put(CacheNames.STOCK_LEVEL, base.entryTtl(Duration.ofSeconds(30)).disableCachingNullValues());
        perCache.put(CacheNames.CATALOG_REPORT, base.entryTtl(Duration.ofMinutes(2)));
        perCache.put(CacheNames.SUPPLIER_QUOTE, base.entryTtl(Duration.ofSeconds(45)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                // Evictions are buffered until the surrounding @Transactional COMMITS.
                // Without this, a rollback would leave the cache evicted (harmless) but a
                // @CachePut would leave the cache holding a value that was never persisted.
                .transactionAware()
                .build();
    }

    private RedisCacheConfiguration defaults() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .computePrefixWith(cacheName -> KEY_PREFIX + cacheName + "::")
                .serializeKeysWith(SerializationPair.fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer));
    }

    /**
     * Default key generator, used when a @Cacheable declares no explicit {@code key}.
     *
     * Spring's built-in {@code SimpleKeyGenerator} produces {@code SimpleKey [a, b]}, which
     * serializes to something you cannot read in redis-cli. This one produces
     * {@code ProductService.findById:3f2a-...}, so the Redis keyspace documents itself.
     *
     * Prefer an explicit {@code key = "#id"} when the method has arguments you do not want in
     * the key (a Pageable, a request object, a user token...).
     */
    @Override
    @Bean
    public KeyGenerator keyGenerator() {
        return (target, method, params) -> {
            String args = params.length == 0
                    ? "none"
                    : Arrays.stream(params)
                            .map(p -> p == null ? "null" : p.toString())
                            .collect(Collectors.joining("-"));
            return target.getClass().getSimpleName() + "." + method.getName() + ":" + args;
        };
    }

    /**
     * Fail-open behaviour: a broken cache must never break a request.
     *
     * Kill Redis ({@code docker compose stop redis}) and hit /api/products/{id}: with this
     * handler the endpoint still answers from Postgres and logs a warning. Comment the bean out
     * and the same request returns 500. That difference is the whole lesson.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache GET failed [cache={}, key={}] - falling back to the database: {}",
                        cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed [cache={}, key={}]: {}", cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                // Careful: swallowing an eviction error means the cache may now be STALE.
                // For money-critical caches, prefer a short TTL over a silent failure.
                log.error("Cache EVICT failed [cache={}, key={}] - entry may be stale until TTL: {}",
                        cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.error("Cache CLEAR failed [cache={}]: {}", cache.getName(), ex.getMessage());
            }
        };
    }
}
