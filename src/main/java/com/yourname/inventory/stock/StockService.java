package com.yourname.inventory.stock;

import com.yourname.inventory.common.cache.CacheNames;
import com.yourname.inventory.product.Product;
import com.yourname.inventory.product.ProductRepository;
import com.yourname.inventory.stock.dto.StockLevelResponse;
import com.yourname.inventory.stock.dto.StockOperationResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The TRANSACTIONAL half of the stock module. Every method here is a database unit of work and
 * nothing more - no locks, no retries, no caching policy decisions beyond eviction.
 *
 * The locking and retry annotations live in {@link StockFacade}, in a different bean, because
 * Spring AOP cannot advise a call a class makes to itself. Keeping "resilience" and
 * "persistence" in separate beans is not ceremony here - it is what makes the annotations work.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ProductRepository productRepository;

    /** Cached read, 30s TTL. Every write method below evicts this key. */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.STOCK_LEVEL, key = "#productId")
    public StockLevelResponse stockLevel(UUID productId) {
        log.info("CACHE MISS -> reading stock level of {}", productId);
        Product p = load(productId);
        return new StockLevelResponse(p.getId(), p.getSku(), p.getStockQty(), p.getVersion());
    }

    /**
     * STRATEGY A - no protection at all. Kept as the control experiment.
     *
     * Read-then-write across two statements with nothing in between guaranteeing exclusivity.
     * Two threads both read stockQty = 1, both decide "enough stock", both write 0. You sold
     * two units and you have one. Hit /api/stock/demo/race?mode=unlocked to watch it.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.STOCK_LEVEL, key = "#productId"),
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#productId")
    })
    public StockOperationResponse reserveUnsafe(UUID productId, int quantity, String orderRef) {
        Product product = load(productId);
        int before = product.getStockQty();

        // A tiny pause widens the race window so the demo is reproducible on a fast machine.
        // It changes nothing about the bug - it only makes it visible every time.
        sleepQuietly(20);

        if (before < quantity) {
            throw new IllegalStateException("Insufficient stock for " + product.getSku()
                    + " (have " + before + ", need " + quantity + ")");
        }

        product.setStockQty(before - quantity);
        productRepository.save(product);
        return response(product, "RESERVE_UNSAFE", quantity, before, orderRef);
    }

    /**
     * STRATEGY B - the database holds the lock (SELECT ... FOR UPDATE).
     *
     * Same code as above, one word different: findByIdForUpdate. Concurrent callers now queue at
     * the row instead of racing, so the read-check-write sequence is atomic. This is the
     * strongest and simplest option WHEN all the state you protect lives in one database.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.STOCK_LEVEL, key = "#productId"),
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#productId")
    })
    public StockOperationResponse reserveWithRowLock(UUID productId, int quantity, String orderRef) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        int before = product.getStockQty();
        sleepQuietly(20);

        if (before < quantity) {
            throw new IllegalStateException("Insufficient stock for " + product.getSku()
                    + " (have " + before + ", need " + quantity + ")");
        }

        product.setStockQty(before - quantity);
        productRepository.save(product);
        return response(product, "RESERVE_ROW_LOCK", quantity, before, orderRef);
    }

    /**
     * STRATEGY C - optimistic: no lock, just @Version, and let the loser fail.
     *
     * REQUIRES_NEW matters: the caller ({@link StockFacade#adjustStock}) retries this method, and
     * a retry is only meaningful in a FRESH transaction. Retrying inside a transaction that has
     * already been marked rollback-only just fails again, differently - a classic head-scratcher.
     *
     * The version conflict is detected at flush/commit time, not here, so the exception is thrown
     * as this method returns. That is also why the retry must sit OUTSIDE the transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.STOCK_LEVEL, key = "#productId"),
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#productId")
    })
    public StockOperationResponse applyDeltaOptimistic(UUID productId, int delta, String reason) {
        Product product = load(productId);
        int before = product.getStockQty();
        int after = before + delta;

        if (after < 0) {
            throw new IllegalStateException("Adjustment would make stock negative for "
                    + product.getSku() + " (have " + before + ", delta " + delta + ")");
        }

        sleepQuietly(15);   // widen the window so version conflicts really happen
        product.setStockQty(after);
        productRepository.saveAndFlush(product);   // flush here => conflict surfaces inside the method

        log.debug("Applied delta {} to {} (version {} -> {}), reason={}",
                delta, product.getSku(), product.getVersion() - 1, product.getVersion(), reason);
        return response(product, "ADJUST", Math.abs(delta), before, reason);
    }

    /**
     * STRATEGY D - two rows, two locks, and the deadlock you get for free if you are careless.
     *
     * Transfer A->B locks A then B; a simultaneous transfer B->A locks B then A. Each holds what
     * the other needs and the database kills one of them. The fix costs one line: ALWAYS take
     * locks in the same global order (here: sorted by UUID). Ordering is the standard deadlock
     * avoidance technique and it applies to Redis locks exactly the same way.
     */
    @Transactional
    @CacheEvict(cacheNames = {CacheNames.STOCK_LEVEL, CacheNames.PRODUCT}, allEntries = true)
    public List<StockOperationResponse> transfer(UUID fromId, UUID toId, int quantity) {
        if (fromId.equals(toId)) {
            throw new IllegalStateException("Source and destination products must differ");
        }

        // Deterministic lock order - the whole point of this method.
        List<UUID> ordered = List.of(fromId, toId).stream().sorted(Comparator.naturalOrder()).toList();
        ordered.forEach(id -> productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id)));

        Product from = load(fromId);
        Product to = load(toId);

        if (from.getStockQty() < quantity) {
            throw new IllegalStateException("Insufficient stock on source " + from.getSku());
        }

        int fromBefore = from.getStockQty();
        int toBefore = to.getStockQty();
        from.setStockQty(fromBefore - quantity);
        to.setStockQty(toBefore + quantity);
        productRepository.save(from);
        productRepository.save(to);

        return List.of(
                response(from, "TRANSFER_OUT", quantity, fromBefore, "to " + to.getSku()),
                response(to, "TRANSFER_IN", quantity, toBefore, "from " + from.getSku()));
    }

    /** Test helper used by the race demo to put a product back to a known stock level. */
    @Transactional
    @CacheEvict(cacheNames = {CacheNames.STOCK_LEVEL, CacheNames.PRODUCT}, allEntries = true)
    public int resetStock(UUID productId, int quantity) {
        Product product = load(productId);
        product.setStockQty(quantity);
        productRepository.saveAndFlush(product);
        return product.getStockQty();
    }

    private Product load(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
    }

    private StockOperationResponse response(Product p, String op, int qty, int before, String note) {
        return new StockOperationResponse(p.getId(), p.getSku(), op, qty, before,
                p.getStockQty(), p.getVersion(), 1, note);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
