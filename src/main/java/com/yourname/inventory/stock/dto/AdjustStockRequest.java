package com.yourname.inventory.stock.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code delta} may be negative (a sale) or positive (a delivery). */
public record AdjustStockRequest(
        int delta,

        @NotBlank(message = "reason is required")
        String reason
) {
}
