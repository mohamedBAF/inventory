package com.yourname.inventory.stock.dto;

import java.util.UUID;

/**
 * Result of a stock operation.
 *
 * {@code attempts} and {@code version} are here purely for learning: they let the HTTP response
 * show you how many times @Retryable had to run the method and how far @Version has advanced.
 */
public record StockOperationResponse(
        UUID productId,
        String sku,
        String operation,
        int requestedQuantity,
        int previousStock,
        int newStock,
        long version,
        int attempts,
        String note
) {
    public StockOperationResponse withAttempts(int attempts) {
        return new StockOperationResponse(productId, sku, operation, requestedQuantity,
                previousStock, newStock, version, attempts, note);
    }

    public StockOperationResponse withNote(String note) {
        return new StockOperationResponse(productId, sku, operation, requestedQuantity,
                previousStock, newStock, version, attempts, note);
    }
}
