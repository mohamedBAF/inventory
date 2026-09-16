package com.yourname.inventory.product.dto;

import java.util.List;

/**
 * Why not just cache Spring Data's {@code Page}?
 *
 * Because {@code PageImpl} has no no-args constructor and no stable JSON shape, so it
 * round-trips through Redis badly (Jackson throws, or silently loses the paging metadata).
 * The habit to build: NEVER cache framework/entity types - cache a small record you own.
 * Caching a JPA entity is the same mistake one level deeper: you would be storing lazy
 * proxies and a detached identity in Redis.
 */
public record ProductPageResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
