package com.yourname.inventory.product;

import com.yourname.inventory.common.cache.CacheNames;
import com.yourname.inventory.common.exception.DuplicateSkuException;
import com.yourname.inventory.product.dto.CreateProductRequest;
import com.yourname.inventory.product.dto.ProductPageResponse;
import com.yourname.inventory.product.dto.ProductResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * LESSON 2 in practice - the four caching annotations on one CRUD service.
 *
 *   @Cacheable  read  : return the cached value, or run the method and store the result
 *   @CachePut   write : ALWAYS run the method, then overwrite the cached value
 *   @CacheEvict write : run the method, then delete cached value(s)
 *   @Caching          : combine several of the above on one method
 *
 * The golden rule of cache invalidation: EVERY write path must touch EVERY cache that could
 * hold the data it changed. Walk through this class and check it for yourself - and then note
 * that stock is changed in StockService too, which is why that class evicts these same caches.
 *
 * Watch it work: {@code logging.level.org.springframework.cache = TRACE} in application.yml
 * prints every cache hit and miss.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    /**
     * A page is cached under a key built from the paging parameters, so page 0 and page 1 are
     * different entries. TTL is 1 minute (CacheConfig) because any create/delete invalidates it.
     *
     * {@code #pageable.pageNumber} instead of the whole Pageable: the default toString of a
     * Pageable contains sort objects and would produce a fragile, unreadable key.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PRODUCT_PAGE,
               key = "'p' + #pageable.pageNumber + ':s' + #pageable.pageSize")
    public ProductPageResponse findAllCached(Pageable pageable) {
        log.info("CACHE MISS -> querying products page={} size={}",
                pageable.getPageNumber(), pageable.getPageSize());

        Page<ProductResponse> page = productRepository.findAll(pageable).map(ProductResponse::from);
        return new ProductPageResponse(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Kept uncached on purpose, so you can compare the two in the logs. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> findAll(Pageable pageable) {
        return productRepository.findAll(pageable).map(ProductResponse::from);
    }

    /**
     * The textbook @Cacheable.
     *
     * {@code unless = "#result == null"} is evaluated AFTER the call ({@code #result} is the
     * return value), while {@code condition} is evaluated BEFORE it (arguments only).
     *
     * Here the method throws rather than returning null, so nothing is cached for a missing id.
     * That leaves the door open to CACHE PENETRATION: a flood of requests for ids that do not
     * exist bypasses Redis and hits Postgres every time. The defence is to cache the "absent"
     * answer for a short TTL - which is what {@code findBySkuNullable} below demonstrates.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PRODUCT, key = "#id")
    public ProductResponse findById(UUID id) {
        log.info("CACHE MISS -> loading product {} from the database", id);
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    /**
     * Cache-penetration protection: null IS cached (Spring stores a NullValue marker, which our
     * serializer understands thanks to enableSpringCacheNullValueSupport()).
     *
     * Trade-off to understand: a product created later stays "not found" until this entry
     * expires. Cache absent values with a SHORT TTL, and evict on create.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PRODUCT, key = "'sku:' + #sku")
    public ProductResponse findBySkuNullable(String sku) {
        log.info("CACHE MISS -> looking up sku {}", sku);
        return productRepository.findBySku(sku).map(ProductResponse::from).orElse(null);
    }

    /**
     * A new product changes every listing, so all page/search entries go.
     * {@code allEntries = true} deletes the whole cache - cheap here because the caches are small
     * and short-lived. On a huge cache prefer evicting precise keys.
     *
     * We also evict the SKU key in case somebody cached "this sku does not exist".
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT_PAGE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.PRODUCT_SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATALOG_REPORT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "'sku:' + #req.sku()")
    })
    public ProductResponse create(CreateProductRequest req) {
        if (productRepository.existsBySku(req.sku()))
            throw new DuplicateSkuException(req.sku());

        Product product = new Product();
        product.setName(req.name());
        product.setSku(req.sku());
        product.setPrice(req.price());
        product.setStockQty(req.stockQty());

        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * @CachePut = write-through: the method always runs, and its RESULT replaces the cached value.
     *
     * Why not just @CacheEvict here? Evicting forces the next reader to hit the database;
     * @CachePut keeps the cache warm. Why not always @CachePut? Because the value you put must
     * be exactly what a reader would have got from {@code findById} - same cache, same key,
     * same type. Get that wrong and you have poisoned the cache with a different shape.
     *
     * The key MUST match findById's key (#id) or you write a second, unused entry - a subtle
     * bug that looks like "caching just doesn't work".
     */
    @Transactional
    @Caching(
            put = @CachePut(cacheNames = CacheNames.PRODUCT, key = "#id"),
            evict = {
                    @CacheEvict(cacheNames = CacheNames.PRODUCT_PAGE, allEntries = true),
                    @CacheEvict(cacheNames = CacheNames.PRODUCT_SEARCH, allEntries = true),
                    @CacheEvict(cacheNames = CacheNames.STOCK_LEVEL, key = "#id")
            }
    )
    public ProductResponse update(UUID id, CreateProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));

        product.setName(req.name());
        product.setPrice(req.price());
        product.setStockQty(req.stockQty());

        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * {@code beforeInvocation = true} evicts BEFORE the method body runs.
     *
     * Default is false: evict only on success. For a delete, prefer true - if the delete fails
     * halfway you would rather have an empty cache (a harmless extra DB read) than an entry
     * describing a row that may no longer exist.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#id", beforeInvocation = true),
            @CacheEvict(cacheNames = CacheNames.STOCK_LEVEL, key = "#id", beforeInvocation = true),
            @CacheEvict(cacheNames = CacheNames.PRODUCT_PAGE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.PRODUCT_SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATALOG_REPORT, allEntries = true)
    })
    public void delete(UUID id) {
        if (!productRepository.existsById(id))
            throw new EntityNotFoundException("Product not found: " + id);
        productRepository.deleteById(id);
    }
}
