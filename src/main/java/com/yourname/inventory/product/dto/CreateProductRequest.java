package com.yourname.inventory.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "SKU is required")
        String sku,

        @Positive(message = "Price must be positive")
        BigDecimal price,

        @Min(value = 0, message = "Stock cannot be negative")
        int stockQty
) {}
