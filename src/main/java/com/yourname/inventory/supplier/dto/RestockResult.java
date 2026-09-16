package com.yourname.inventory.supplier.dto;

import java.util.UUID;

/**
 * {@code degraded = true} means every retry failed and you are looking at the hand-written
 * fallback, not a real answer from the supplier.
 */
public record RestockResult(
        UUID productId,
        String sku,
        int requestedQuantity,
        boolean ordered,
        boolean degraded,
        int attempts,
        String message
) {
}
