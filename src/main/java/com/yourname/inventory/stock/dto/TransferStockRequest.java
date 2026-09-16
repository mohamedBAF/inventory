package com.yourname.inventory.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferStockRequest(
        @NotNull UUID fromProductId,
        @NotNull UUID toProductId,
        @Min(1) int quantity
) {
}
