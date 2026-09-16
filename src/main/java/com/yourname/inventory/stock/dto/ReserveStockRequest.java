package com.yourname.inventory.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ReserveStockRequest(
        @Min(value = 1, message = "Quantity must be at least 1")
        int quantity,

        @NotBlank(message = "orderRef is required (any string - it only shows up in the logs)")
        String orderRef
) {
}
