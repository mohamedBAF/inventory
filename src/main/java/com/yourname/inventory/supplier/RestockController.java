package com.yourname.inventory.supplier;

import com.yourname.inventory.common.retry.RetryAttemptTracker;
import com.yourname.inventory.supplier.dto.RestockResult;
import com.yourname.inventory.supplier.dto.SupplierQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Retry endpoints. The {@code failurePercent} query parameter controls how often the fake
 * supplier fails, so you can drive the retry logic deterministically:
 *
 *   failurePercent=0    always succeeds, attempts = 1
 *   failurePercent=60   usually needs a retry or two - watch "attempts" in the response
 *   failurePercent=100  always fails - see the exhausted-retries behaviour of each endpoint
 */
@RestController
@RequestMapping("/api/restock")
@RequiredArgsConstructor
public class RestockController {

    private final SupplierGateway supplierGateway;
    private final RestockService restockService;
    private final RetryAttemptTracker attemptTracker;

    /**
     * Raw @Retryable, no fallback: with failurePercent=100 this returns HTTP 503 after 4 calls
     * (1 + 3 retries) and about 1.4s of backoff.
     */
    @GetMapping("/quote/{sku}")
    public SupplierQuote quote(@PathVariable String sku,
                               @RequestParam(defaultValue = "60") int failurePercent) {
        attemptTracker.reset();
        return supplierGateway.fetchQuote(sku, failurePercent);
    }

    /** Same, but bounded by a 2s overall time budget instead of an attempt count. */
    @GetMapping("/quote/{sku}/budgeted")
    public SupplierQuote quoteWithinBudget(@PathVariable String sku,
                                           @RequestParam(defaultValue = "60") int failurePercent) {
        attemptTracker.reset();
        return supplierGateway.fetchQuoteWithinBudget(sku, failurePercent);
    }

    /**
     * Cached quote: the first call may retry, the next ones hit Redis for 45s.
     * Call it twice with failurePercent=100 - the second call cannot fail, because it never runs.
     */
    @GetMapping("/quote/{sku}/cached")
    public SupplierQuote cachedQuote(@PathVariable String sku,
                                     @RequestParam(defaultValue = "30") int failurePercent) {
        attemptTracker.reset();
        return restockService.quote(sku, failurePercent);
    }

    /**
     * The full flow: distributed lock + retrying supplier call + retrying optimistic stock write
     * + graceful degradation. With failurePercent=100 you get HTTP 200 and degraded=true, never a 500.
     */
    @PostMapping("/{productId}")
    public RestockResult restock(@PathVariable UUID productId,
                                 @RequestParam(defaultValue = "50") int quantity,
                                 @RequestParam(defaultValue = "40") int failurePercent) {
        return restockService.restock(productId, quantity, failurePercent);
    }
}
