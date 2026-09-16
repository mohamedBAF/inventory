package com.yourname.inventory.common.cache;

/**
 * Central registry of cache names.
 *
 * WHY a constants class?
 *  - {@code @Cacheable("product")} with a raw string is a typo waiting to happen:
 *    a misspelled name silently creates a *different* cache that nothing ever evicts.
 *  - Every TTL is configured per name in {@code CacheConfig}, so the name is the
 *    contract between the annotation and the configuration.
 */
public final class CacheNames {

    private CacheNames() {
    }

    /** One product by id. Read often, changes rarely -> long TTL. */
    public static final String PRODUCT = "product";

    /** A page of products. Changes on every create/delete -> short TTL. */
    public static final String PRODUCT_PAGE = "productPage";

    /** Search results, cached conditionally (see CatalogService). */
    public static final String PRODUCT_SEARCH = "productSearch";

    /** Stock level of one product. Very volatile -> very short TTL. */
    public static final String STOCK_LEVEL = "stockLevel";

    /** Expensive aggregation report. Protected against stampede with sync = true. */
    public static final String CATALOG_REPORT = "catalogReport";

    /** Quotes from the (slow, flaky) supplier API. */
    public static final String SUPPLIER_QUOTE = "supplierQuote";
}
