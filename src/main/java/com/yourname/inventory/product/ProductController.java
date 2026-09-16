package com.yourname.inventory.product;

import com.yourname.inventory.product.dto.CreateProductRequest;
import com.yourname.inventory.product.dto.ProductPageResponse;
import com.yourname.inventory.product.dto.ProductResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public Page<ProductResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return productService.findAll(pageable);
    }

    /**
     * Same listing, served from Redis (cache "productPage", 1 min TTL).
     *
     * Compare the two: hit /api/products twice and you see two SQL statements in the log;
     * hit /api/products/cached twice and the second one logs nothing.
     */
    @GetMapping("/cached")
    public ProductPageResponse listCached(@PageableDefault(size = 20) Pageable pageable) {
        return productService.findAllCached(pageable);
    }

    /**
     * Demonstrates caching a MISS. Ask for a SKU that does not exist twice: the second call
     * never reaches the database, because the null answer itself is cached.
     */
    @GetMapping("/by-sku/{sku}")
    public ResponseEntity<ProductResponse> getBySku(@PathVariable String sku) {
        ProductResponse found = productService.findBySkuNullable(sku);
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(found);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable UUID id) {
        return productService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest req) {
        return productService.create(req);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody CreateProductRequest req) {
        return productService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        productService.delete(id);
    }
}
