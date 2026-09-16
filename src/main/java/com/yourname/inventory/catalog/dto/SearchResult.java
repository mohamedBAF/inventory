package com.yourname.inventory.catalog.dto;

import com.yourname.inventory.product.dto.ProductResponse;

import java.util.List;

public record SearchResult(String term, List<ProductResponse> items, int count) {
}
