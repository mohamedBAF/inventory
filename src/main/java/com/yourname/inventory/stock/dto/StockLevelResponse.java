package com.yourname.inventory.stock.dto;

import java.util.UUID;

/** Cached view of a stock level (cache "stockLevel", 30s TTL). */
public record StockLevelResponse(UUID productId, String sku, int stockQty, long version) {
}
