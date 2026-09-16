package com.yourname.inventory.stock.dto;

import java.util.List;

/**
 * The output of the concurrency playground.
 *
 * {@code oversold = true} means the run sold stock that did not exist - i.e. the strategy you
 * picked did NOT protect the critical section. Run mode=unlocked to see it happen.
 */
public record RaceDemoResponse(
        String mode,
        String explanation,
        int threads,
        int quantityEach,
        int initialStock,
        int expectedFinalStock,
        int actualFinalStock,
        int succeeded,
        int failed,
        boolean oversold,
        long elapsedMs,
        List<String> sampleErrors
) {
}
