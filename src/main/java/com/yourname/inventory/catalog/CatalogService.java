package com.yourname.inventory.catalog;

import com.yourname.inventory.catalog.dto.CachedPayload;
import com.yourname.inventory.catalog.dto.CatalogReport;
import com.yourname.inventory.catalog.dto.SearchResult;
import com.yourname.inventory.common.cache.CacheNames;
import com.yourname.inventory.product.Product;
import com.yourname.inventory.product.ProductRepository;
import com.yourname.inventory.product.dto.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Three caching techniques beyond plain @Cacheable:
 *
 *   1. sync = true            - stampede (dog-pile) protection.
 *   2. manual cache-aside     - what @Cacheable does, written by hand, so you can see it.
 *   3. condition / unless     - caching only what is worth caching.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private static final String TOP_PRODUCTS_KEY = "inventory:manual:top-products:";

    private final ProductRepository productRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * TECHNIQUE 1 - {@code sync = true}.
     *
     * Scenario: a report that takes 1.5s, requested by 50 users, and the cache entry has just
     * expired. Without sync, all 50 miss at once and all 50 run the query - the cache stampede.
     * Your database gets its worst traffic spike exactly when the cache expires.
     *
     * With sync = true, one thread computes while the others WAIT for its result.
     *
     * Restrictions worth remembering: sync works with a single cache name only, and it cannot be
     * combined with {@code unless} or with @CachePut on the same method.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.CATALOG_REPORT, key = "'full-report'", sync = true)
    public CatalogReport fullReport() {
        long start = System.currentTimeMillis();
        log.info("CACHE MISS -> computing the full catalog report (slow)...");

        List<Product> all = productRepository.findAll();
        sleepQuietly(1_500);   // stand-in for a heavy aggregation / several joins

        long units = all.stream().mapToLong(Product::getStockQty).sum();
        BigDecimal value = all.stream()
                .map(p -> p.getPrice().multiply(BigDecimal.valueOf(p.getStockQty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CatalogReport.TopProduct> top = productRepository.findTopByStock(PageRequest.of(0, 5))
                .stream()
                .map(p -> new CatalogReport.TopProduct(p.getId(), p.getSku(), p.getName(),
                        p.getStockQty(), p.getPrice()))
                .toList();

        return new CatalogReport(all.size(), units, value, top, LocalDateTime.now(),
                System.currentTimeMillis() - start);
    }

    /**
     * TECHNIQUE 2 - cache-aside by hand.
     *
     * Look up, miss, compute, store with a TTL. That is the entire pattern @Cacheable implements.
     * Write it once yourself and the annotation stops being magic.
     *
     * When to drop down to this level:
     *   - you need the hit/miss signal in the response (as here)
     *   - the TTL depends on the data (see the jitter below)
     *   - the key is not derivable from the method arguments (tenant id, locale, feature flag)
     *   - you want a Redis structure a cache cannot express (sorted set, counter, HyperLogLog)
     *
     * TTL JITTER: every entry expiring after exactly 120s means every entry expires TOGETHER
     * after a deploy that warms them together - a synchronised stampede. A random 0-30s on top
     * spreads the expiries out. Small trick, big difference under load.
     */
    @Transactional(readOnly = true)
    public CachedPayload<List<CatalogReport.TopProduct>> topProducts(int limit) {
        long start = System.currentTimeMillis();
        String key = TOP_PRODUCTS_KEY + limit;

        @SuppressWarnings("unchecked")
        List<CatalogReport.TopProduct> cached =
                (List<CatalogReport.TopProduct>) redisTemplate.opsForValue().get(key);

        if (cached != null) {
            Long ttl = redisTemplate.getExpire(key);
            log.info("Manual cache HIT for {}", key);
            return new CachedPayload<>(cached, true, System.currentTimeMillis() - start, key, ttl);
        }

        log.info("Manual cache MISS for {} -> querying the database", key);
        List<CatalogReport.TopProduct> fresh = productRepository.findTopByStock(PageRequest.of(0, limit))
                .stream()
                .map(p -> new CatalogReport.TopProduct(p.getId(), p.getSku(), p.getName(),
                        p.getStockQty(), p.getPrice()))
                .toList();

        Duration ttl = Duration.ofSeconds(120 + ThreadLocalRandom.current().nextInt(30));
        redisTemplate.opsForValue().set(key, fresh, ttl);

        return new CachedPayload<>(fresh, false, System.currentTimeMillis() - start, key, ttl.toSeconds());
    }

    /** Lets you watch the TTL count down / force a refresh from the admin endpoints. */
    public boolean evictTopProducts(int limit) {
        return Boolean.TRUE.equals(redisTemplate.delete(TOP_PRODUCTS_KEY + limit));
    }

    /**
     * TECHNIQUE 3 - condition and unless: two filters, evaluated at different times.
     *
     *   condition - BEFORE the call, sees the ARGUMENTS. Here: do not cache one- or two-letter
     *               searches. They match half the catalogue and would fill Redis with huge,
     *               useless entries ("cache pollution").
     *   unless    - AFTER the call, sees {@code #result}. Here: do not cache empty results, so a
     *               typo today does not keep returning "nothing found" after the product exists.
     *
     * Both can be true at once; {@code unless} wins because it is checked last.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PRODUCT_SEARCH,
               key = "#term.toLowerCase()",
               condition = "#term.length() >= 3",
               unless = "#result.items().isEmpty()")
    public SearchResult search(String term) {
        log.info("CACHE MISS (or not cacheable) -> searching for '{}'", term);
        sleepQuietly(200);

        List<ProductResponse> items = productRepository.search(term).stream()
                .map(ProductResponse::from)
                .toList();
        return new SearchResult(term, items, items.size());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
