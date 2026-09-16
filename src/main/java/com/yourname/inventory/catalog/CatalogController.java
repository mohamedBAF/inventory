package com.yourname.inventory.catalog;

import com.yourname.inventory.catalog.dto.CachedPayload;
import com.yourname.inventory.catalog.dto.CatalogReport;
import com.yourname.inventory.catalog.dto.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    /**
     * Stampede demo. Takes ~1.5s cold, ~2ms warm (2 min TTL).
     *
     * Fire 20 at once against a cold cache and check the log:
     *   for i in $(seq 20); do curl -s localhost:8080/api/catalog/report &amp; done
     * "computing the full catalog report" appears ONCE, because of sync = true.
     */
    @GetMapping("/report")
    public CatalogReport report() {
        return catalogService.fullReport();
    }

    /** Manual cache-aside: the response itself tells you cacheHit, elapsedMs and the TTL left. */
    @GetMapping("/top-products")
    public CachedPayload<List<CatalogReport.TopProduct>> topProducts(
            @RequestParam(defaultValue = "5") int limit) {
        return catalogService.topProducts(limit);
    }

    @DeleteMapping("/top-products")
    public String evictTopProducts(@RequestParam(defaultValue = "5") int limit) {
        boolean removed = catalogService.evictTopProducts(limit);
        return removed ? "Evicted. The next call will be a miss." : "Nothing cached for limit=" + limit;
    }

    /**
     * condition / unless demo:
     *   ?q=ab       - never cached (shorter than 3 characters)
     *   ?q=widget   - cached if it matches something
     *   ?q=zzzzz    - not cached, because the result is empty
     * Repeat each one and compare the response times.
     */
    @GetMapping("/search")
    public SearchResult search(@RequestParam("q") String term) {
        return catalogService.search(term);
    }
}
