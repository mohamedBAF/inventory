package com.yourname.inventory.supplier;

import com.yourname.inventory.common.cache.CacheNames;
import com.yourname.inventory.common.lock.DistributedLock;
import com.yourname.inventory.common.retry.RetryAttemptTracker;
import com.yourname.inventory.product.Product;
import com.yourname.inventory.product.ProductRepository;
import com.yourname.inventory.stock.StockFacade;
import com.yourname.inventory.supplier.dto.RestockResult;
import com.yourname.inventory.supplier.dto.SupplierQuote;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Where caching, retrying and locking meet in one business flow.
 *
 * CACHE OUTSIDE, RETRY INSIDE. {@link #quote} is @Cacheable and delegates to the @Retryable
 * gateway, so a cached answer costs zero calls and zero retries. Doing it the other way round
 * (retry outside, cache inside) would retry a method that can only ever return the cached value.
 *
 * They are separate beans on purpose: two annotations that both rely on proxies are far easier
 * to reason about when the call between them crosses a bean boundary.
 */
@Service
@RequiredArgsConstructor
public class RestockService {

    private static final Logger log = LoggerFactory.getLogger(RestockService.class);

    private final SupplierGateway supplierGateway;
    private final ProductRepository productRepository;
    private final StockFacade stockFacade;
    private final RetryAttemptTracker attemptTracker;

    /**
     * Cached quote (45s TTL). Note {@code key = "#sku"} and NOT the whole argument list:
     * {@code failurePercent} is a test knob, not part of the identity of the data. Putting an
     * irrelevant argument in the key is one of the most common reasons a cache "never hits".
     */
    @Cacheable(cacheNames = CacheNames.SUPPLIER_QUOTE, key = "#sku")
    public SupplierQuote quote(String sku, int failurePercent) {
        log.info("CACHE MISS -> asking the supplier for a quote on {}", sku);
        return supplierGateway.fetchQuote(sku, failurePercent);
    }

    /**
     * The FALLBACK pattern, written by hand.
     *
     * spring-retry had @Recover for this; Spring Framework 7 does not, so you catch the final
     * exception yourself. Honestly, the explicit version reads better: you can see exactly what
     * the degraded answer is, and you are forced to decide what it should be.
     *
     * Degrading well is a design decision, not a code trick:
     *   - a quote endpoint can return yesterday's price marked "stale"
     *   - a restock endpoint can queue the order for later (what we pretend to do here)
     *   - a payment endpoint must NOT invent a fallback - it has to fail loudly
     *
     * The @DistributedLock makes the whole flow idempotent-ish across instances: two operators
     * clicking "restock" at the same time will not place two purchase orders.
     */
    @DistributedLock(key = "'restock:' + #productId", waitTimeMs = 1_000, leaseTimeMs = 15_000)
    public RestockResult restock(UUID productId, int quantity, int failurePercent) {
        attemptTracker.reset();

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));

        try {
            SupplierQuote supplierQuote = supplierGateway.fetchQuote(product.getSku(), failurePercent);

            // Raising stock is a normal optimistic-locking write, so it goes through the retrying facade.
            stockFacade.adjustStock(productId, quantity, "restock from " + supplierQuote.supplier());

            return new RestockResult(productId, product.getSku(), quantity, true, false,
                    supplierQuote.attempts(),
                    "Ordered %d units at %s each, lead time %d days"
                            .formatted(quantity, supplierQuote.unitPrice(), supplierQuote.leadTimeDays()));

        } catch (SupplierUnavailableException ex) {
            // Every retry is exhausted. Degrade instead of throwing a 500 at the operator.
            log.error("Supplier unreachable after {} attempts - queueing restock for later: {}",
                    attemptTracker.current(), ex.getMessage());

            return new RestockResult(productId, product.getSku(), quantity, false, true,
                    attemptTracker.current(),
                    "Supplier unreachable; restock request queued for manual follow-up. Stock unchanged.");
        }
    }
}
