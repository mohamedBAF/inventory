package com.yourname.inventory.stock;

import com.yourname.inventory.stock.dto.RaceDemoResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The concurrency playground: fire N simultaneous reservations at ONE product and compare the
 * four strategies. This is the fastest way to feel the difference between them.
 *
 *   mode=unlocked    -> oversells. The bug, reproduced on demand.
 *   mode=redis-lock  -> correct. Works even across several app instances.
 *   mode=row-lock    -> correct. The database serialises the writers.
 *   mode=optimistic  -> correct. Conflicts are detected and retried (watch the attempt counts).
 *
 * Threads are VIRTUAL (Java 21), so 200 concurrent "customers" cost almost nothing - which is
 * also why the blocking spin-wait inside RedisLockService is acceptable in this project.
 */
@Service
@RequiredArgsConstructor
public class StockRaceDemoService {

    private static final Logger log = LoggerFactory.getLogger(StockRaceDemoService.class);

    private final StockService stockService;
    private final StockFacade stockFacade;

    public RaceDemoResponse run(UUID productId, String mode, int threads, int quantityEach, int initialStock) {
        stockService.resetStock(productId, initialStock);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch startGun = new CountDownLatch(1);

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        // Everybody waits on the same latch, so the requests really do collide.
                        startGun.await();
                        execute(mode, productId, quantityEach, "race-" + index);
                        succeeded.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        if (errors.size() < 5) {
                            errors.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                    }
                });
            }
            startGun.countDown();
        }   // close() waits for every virtual thread to finish
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        int actual = stockService.stockLevel(productId).stockQty();
        int expected = Math.max(0, initialStock - (succeeded.get() * quantityEach));
        boolean oversold = actual != expected;

        if (oversold) {
            log.warn("OVERSOLD! mode={} expected stock {} but found {}", mode, expected, actual);
        }

        return new RaceDemoResponse(mode, explain(mode), threads, quantityEach, initialStock,
                expected, actual, succeeded.get(), failed.get(), oversold, elapsedMs, errors);
    }

    private void execute(String mode, UUID productId, int quantity, String ref) {
        switch (mode) {
            case "unlocked"   -> stockService.reserveUnsafe(productId, quantity, ref);
            case "redis-lock" -> stockFacade.reserveWithDistributedLock(productId, quantity, ref);
            case "row-lock"   -> stockFacade.reserveWithRowLock(productId, quantity, ref);
            case "optimistic" -> stockFacade.adjustStock(productId, -quantity, ref);
            default -> throw new IllegalArgumentException(
                    "Unknown mode '" + mode + "'. Use: unlocked | redis-lock | row-lock | optimistic");
        }
    }

    private String explain(String mode) {
        return switch (mode) {
            case "unlocked" -> "No protection: every thread reads the same stock value and they all "
                    + "write over each other. Expect oversold=true and a final stock that does not "
                    + "match the number of successful reservations.";
            case "redis-lock" -> "Redis SET NX PX lock, one key per product. Threads queue up to "
                    + "waitTimeMs; whoever cannot get in within that window fails with 409. Works "
                    + "across multiple application instances, which is what the database row lock "
                    + "cannot do for state that is not in the database.";
            case "row-lock" -> "SELECT ... FOR UPDATE. PostgreSQL queues the writers on the row "
                    + "itself. Simplest correct option when all the state lives in one database.";
            case "optimistic" -> "@Version + @Retryable. Nothing blocks; conflicting writers lose "
                    + "and are replayed on fresh data. Fastest when conflicts are rare, worst when "
                    + "they are common (watch 'failed' rise once retries are exhausted).";
            default -> "Unknown mode.";
        };
    }
}
