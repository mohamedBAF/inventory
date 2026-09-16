package com.yourname.inventory.supplier.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SupplierQuote(
        String sku,
        BigDecimal unitPrice,
        int leadTimeDays,
        String supplier,
        int attempts,
        LocalDateTime quotedAt
) {
}
