package com.yourname.inventory.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Result of an expensive aggregation - exactly the kind of thing worth caching. */
public record CatalogReport(
        int totalProducts,
        long totalUnitsInStock,
        BigDecimal inventoryValue,
        List<TopProduct> topByStock,
        LocalDateTime generatedAt,
        long computationMs
) {
    public record TopProduct(UUID id, String sku, String name, int stockQty, BigDecimal price) {
    }
}
