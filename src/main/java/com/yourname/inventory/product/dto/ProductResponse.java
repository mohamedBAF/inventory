package com.yourname.inventory.product.dto;

import com.yourname.inventory.product.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String sku,
        BigDecimal price,
        int stockQty,
        long version,
        LocalDateTime createdAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getName(), p.getSku(),
                p.getPrice(), p.getStockQty(), p.getVersion(), p.getCreatedAt()
        );
    }
}
