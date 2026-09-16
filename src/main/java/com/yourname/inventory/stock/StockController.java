package com.yourname.inventory.stock;

import com.yourname.inventory.common.retry.RetryAttemptTracker;
import com.yourname.inventory.stock.dto.AdjustStockRequest;
import com.yourname.inventory.stock.dto.RaceDemoResponse;
import com.yourname.inventory.stock.dto.ReserveStockRequest;
import com.yourname.inventory.stock.dto.StockLevelResponse;
import com.yourname.inventory.stock.dto.StockOperationResponse;
import com.yourname.inventory.stock.dto.TransferStockRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Every locking strategy, one endpoint each, so you can curl them side by side.
 *
 * Suggested order when learning:
 *   1. GET    /api/stock/{id}                      - see a cache hit/miss in the log
 *   2. POST   /api/stock/demo/race?mode=unlocked   - break it on purpose
 *   3. POST   /api/stock/demo/race?mode=redis-lock - fix it with a Redis lock
 *   4. POST   /api/stock/demo/race?mode=row-lock   - fix it with the database
 *   5. POST   /api/stock/demo/race?mode=optimistic - fix it with @Version + @Retryable
 */
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final StockFacade stockFacade;
    private final StockRaceDemoService raceDemoService;
    private final RetryAttemptTracker attemptTracker;

    /** Cached for 30s (cache "stockLevel"). Every write endpoint below evicts it. */
    @GetMapping("/{productId}")
    public StockLevelResponse stockLevel(@PathVariable UUID productId) {
        return stockService.stockLevel(productId);
    }

    /** Redis distributed lock: concurrent callers queue for up to 3s, then get 409. */
    @PostMapping("/{productId}/reserve")
    public StockOperationResponse reserve(@PathVariable UUID productId,
                                          @Valid @RequestBody ReserveStockRequest req) {
        return stockFacade.reserveWithDistributedLock(productId, req.quantity(), req.orderRef());
    }

    /** Database row lock (SELECT ... FOR UPDATE), with a retry on lock timeout. */
    @PostMapping("/{productId}/reserve-row-lock")
    public StockOperationResponse reserveRowLock(@PathVariable UUID productId,
                                                 @Valid @RequestBody ReserveStockRequest req) {
        attemptTracker.reset();
        return stockFacade.reserveWithRowLock(productId, req.quantity(), req.orderRef());
    }

    /** No protection at all - here so you can reproduce the bug the others prevent. */
    @PostMapping("/{productId}/reserve-unsafe")
    public StockOperationResponse reserveUnsafe(@PathVariable UUID productId,
                                                @Valid @RequestBody ReserveStockRequest req) {
        return stockService.reserveUnsafe(productId, req.quantity(), req.orderRef())
                .withNote("UNSAFE: no lock. Correct only because nothing else ran at the same time.");
    }

    /**
     * Optimistic locking + @Retryable. The {@code attempts} field in the response tells you how
     * many times the method had to run - fire this concurrently and watch it climb above 1.
     */
    @PatchMapping("/{productId}/adjust")
    public StockOperationResponse adjust(@PathVariable UUID productId,
                                         @Valid @RequestBody AdjustStockRequest req) {
        attemptTracker.reset();   // count retries for THIS request only
        return stockFacade.adjustStock(productId, req.delta(), req.reason());
    }

    /** Two rows, one lock, taken in a deterministic order to avoid deadlocks. */
    @PostMapping("/transfer")
    public List<StockOperationResponse> transfer(@Valid @RequestBody TransferStockRequest req) {
        return stockFacade.transfer(req.fromProductId(), req.toProductId(), req.quantity());
    }

    /**
     * Fail-fast lock (waitTime = 0). Call it twice within 3 seconds from two terminals:
     * the first returns 200 after ~3s, the second returns 409 immediately.
     */
    @PostMapping("/{productId}/audit")
    public String audit(@PathVariable UUID productId) {
        return stockFacade.runExclusiveAudit(productId);
    }

    /** @ConcurrencyLimit(2): a bulkhead, not a lock. Fire 5 of these and watch them pair up. */
    @PostMapping("/{productId}/recount")
    public String recount(@PathVariable UUID productId) {
        return stockFacade.expensiveRecount(productId);
    }

    /**
     * THE demo endpoint. Resets the product to {@code initialStock}, then fires {@code threads}
     * simultaneous reservations of {@code quantityEach} and reports whether stock was oversold.
     *
     * curl -X POST "localhost:8080/api/stock/demo/race?productId=<id>&mode=unlocked"
     */
    @PostMapping("/demo/race")
    public RaceDemoResponse race(@RequestParam UUID productId,
                                 @RequestParam(defaultValue = "unlocked") String mode,
                                 @RequestParam(defaultValue = "20") int threads,
                                 @RequestParam(defaultValue = "1") int quantityEach,
                                 @RequestParam(defaultValue = "10") int initialStock) {
        return raceDemoService.run(productId, mode, threads, quantityEach, initialStock);
    }
}
