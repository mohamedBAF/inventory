# LEARNING.md — Redis caching, @Retryable and distributed locks

A guided tour of this project. Every section is: **the idea → the code → a curl you can run → what to look for.**

Keep a second terminal open on the application log the whole time. Most of the lessons here are
visible in the log, not in the HTTP response.

---

## 0. Start everything

```bash
docker compose up -d          # postgres + redis
./mvnw spring-boot:run
```

Sanity check:

```bash
docker exec -it inventory-redis redis-cli ping     # PONG
curl -s localhost:8080/api/products | head
```

Create a product to play with and keep its id handy:

```bash
PID=$(curl -s -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-1","name":"Blue Widget","price":9.99,"stockQty":100}' \
  | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo $PID
```

Useful watch window:

```bash
watch -n1 "docker exec inventory-redis redis-cli --scan --pattern 'inventory:*'"
```

---

## 1. Caching

### 1.1 The wiring

| File | What it teaches |
|---|---|
| `config/RedisConfig.java` | serializers, why Boot 4 uses `GenericJacksonJsonRedisSerializer` (Jackson 3), the type-validator allow-list |
| `config/CacheConfig.java` | per-cache TTL, key prefix, `transactionAware()`, custom `KeyGenerator`, fail-open `CacheErrorHandler` |
| `common/cache/CacheNames.java` | cache names as constants, never as string literals |

Two things in `CacheConfig` are worth more than the rest put together:

* **`transactionAware()`** — cache writes are deferred to after the transaction commits. Without it, a
  rolled-back transaction leaves a cache entry describing data that never existed.
* **the `CacheErrorHandler`** — Redis being down logs a warning and falls through to the database. A
  cache is an optimisation; it must never be a new single point of failure.

### 1.2 Cached vs uncached, side by side

```bash
time curl -s "localhost:8080/api/products?page=0&size=20"          # uncached, always hits the DB
time curl -s "localhost:8080/api/products/cached?page=0&size=20"   # first call slow
time curl -s "localhost:8080/api/products/cached?page=0&size=20"   # second call fast
```

`ProductService.findAllCached` returns a **`ProductPageResponse`, not a `Page`**. `PageImpl` has no
default constructor and no stable serialized shape — cache it and you get a deserialization error on
the next deploy. Cache your own DTO, always.

### 1.3 Eviction

```bash
curl -s localhost:8080/api/admin/cache                       # key counts per cache
curl -s localhost:8080/api/admin/cache/productPage/keys      # the exact keys
curl -s -X POST localhost:8080/api/products -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-2","name":"Red Widget","price":12.50,"stockQty":40}'
curl -s localhost:8080/api/admin/cache/productPage/keys      # gone
```

`ProductService.create/update/delete` use `@Caching` to combine a `@CachePut` (refresh the single
entry) with several `@CacheEvict(allEntries = true)` (drop every list/search/report that may now be
stale). `delete` uses `beforeInvocation = true` so the entry is dropped even if the delete throws.

### 1.4 Cache penetration — caching `null`

```bash
curl -i localhost:8080/api/products/by-sku/DOES-NOT-EXIST      # 404
curl -i localhost:8080/api/products/by-sku/DOES-NOT-EXIST      # 404, but no SQL in the log
```

If missing keys are not cached, anyone can bypass your cache entirely by requesting ids that do not
exist. The cache config enables null-value support for exactly this.

### 1.5 Cache stampede — `sync = true`

```bash
curl -s -X DELETE localhost:8080/api/admin/cache/catalogReport
for i in $(seq 20); do curl -s localhost:8080/api/catalog/report > /dev/null & done; wait
```

`CatalogService.fullReport` takes ~1.5s and is `@Cacheable(sync = true)`. The log shows
`computing the full catalog report` **once**, not twenty times. Without `sync`, twenty concurrent
misses all run the expensive query — the "thundering herd" that takes a database down the moment a
hot key expires.

Caveat worth knowing: `sync = true` locks **per JVM**, not per cluster. Across many instances you get
one computation *per instance*. For true cluster-wide single-flight, use the distributed lock from §3.

### 1.6 `condition`, `unless`, and manual cache-aside

```bash
curl -s "localhost:8080/api/catalog/search?q=ab"       # too short -> condition false, never cached
curl -s "localhost:8080/api/catalog/search?q=wid"      # cached if non-empty
curl -s "localhost:8080/api/catalog/search?q=zzzzz"    # empty -> unless kicks in, not cached
```

* `condition` is evaluated **before** the method runs (on the arguments).
* `unless` is evaluated **after** (it can see `#result`).

```bash
curl -s "localhost:8080/api/catalog/top-products?limit=5"      # cacheHit:false
curl -s "localhost:8080/api/catalog/top-products?limit=5"      # cacheHit:true + ttlSeconds
curl -s -X DELETE "localhost:8080/api/catalog/top-products?limit=5"
```

`CatalogService.topProducts` does the cache-aside dance by hand with `RedisTemplate` — get, miss,
compute, put with a TTL. Worth reading once so you know exactly what `@Cacheable` does for you. It
also adds **TTL jitter** (`120s + random(30)`) so a batch of keys written together does not all
expire in the same second.

### 1.7 Key design

`RestockService.quote` is keyed on `#sku` alone, even though the method takes a second argument. A
key must contain everything that identifies the data and **nothing else** — an irrelevant argument in
the key is the usual reason a cache "never hits".

---

## 2. `@Retryable`

> This project uses **native Spring Framework 7 retry**
> (`org.springframework.resilience.annotation.Retryable`), enabled by `@EnableResilientMethods` in
> `config/ResilienceConfig.java`. No `spring-retry` dependency.

Differences from the old `spring-retry` you will find in most tutorials:

| spring-retry | Spring Framework 7 |
|---|---|
| `maxAttempts = 4` (includes the first call) | `maxRetries = 3` (retries *after* the first call) |
| `@Backoff(delay, multiplier, maxDelay)` | inline `delay`, `multiplier`, `maxDelay`, `jitter` |
| `include` / `exclude` | `includes` / `excludes` / `predicate` |
| `@Recover` fallback method | **none — you write the fallback yourself** |

### 2.1 Backoff and jitter

```bash
curl -s "localhost:8080/api/restock/quote/WIDGET-1?failurePercent=70"
```

`SupplierGateway.fetchQuote` is `maxRetries = 3, delay = 200, multiplier = 2.0, jitter = 50, maxDelay = 2000`.
Watch the log: attempts land at roughly 200ms, 400ms, 800ms — each ±50ms.

**Jitter is not decoration.** Without it, a thousand clients that failed at the same instant retry at
the same instant, and your recovering service is knocked over by its own clients.

With `failurePercent=100` the exception escapes after the last attempt and
`GlobalExceptionHandler` turns it into **503**, because there is no `@Recover`.

### 2.2 Time budget instead of attempt count

```bash
curl -s "localhost:8080/api/restock/quote/WIDGET-1/budgeted?failurePercent=100"
```

`fetchQuoteWithinBudget` uses `timeout = 2000`: retry as often as you like, but stop after 2s total.
This is usually the better bound when a caller is waiting — an attempt count says nothing about how
long the user has been staring at a spinner.

### 2.3 Writing the fallback by hand

```bash
curl -s -X POST "localhost:8080/api/restock/$PID?quantity=50&failurePercent=100"
```

Returns **200 with `"degraded": true`** instead of a 503. `RestockService.restock` catches
`SupplierUnavailableException` after the retries are exhausted and returns a degraded result.

Degrading is a design decision, not a code trick:

* a quote endpoint may return yesterday's price marked *stale*
* a restock endpoint may queue the order (what we do here)
* a **payment** endpoint must never invent a fallback — it has to fail loudly

### 2.4 What deserves a retry

| Retry | Do not retry |
|---|---|
| 503, 429, connection resets, read timeouts | 400, 404, 422 — the next attempt fails identically |
| DB deadlocks, lock timeouts, optimistic-lock conflicts | 401/403 — fix the credentials, not the retry count |
| Idempotent GET/PUT/DELETE | non-idempotent POST **without** an idempotency key: a retry may charge twice |

This is why every `@Retryable` here lists `includes = ...` explicitly. Retrying everything means
retrying bugs.

### 2.5 Retry + cache together

```bash
curl -s "localhost:8080/api/restock/quote/WIDGET-1/cached?failurePercent=100"   # may 503
curl -s "localhost:8080/api/restock/quote/WIDGET-1/cached?failurePercent=0"     # succeeds, now cached
curl -s "localhost:8080/api/restock/quote/WIDGET-1/cached?failurePercent=100"   # 200 - never executed
```

**Cache outside, retry inside.** A cache hit costs zero calls and zero retries. The other order —
retry outside, cache inside — retries a method that can only return the cached value.

They are two separate beans (`RestockService` → `SupplierGateway`) on purpose. Which brings us to the
one rule that catches everyone:

> **Proxy self-invocation.** `@Cacheable`, `@Retryable`, `@Transactional` and `@DistributedLock` are
> all implemented with proxies. A plain `this.method()` call inside the same bean **bypasses the
> proxy entirely** and the annotation silently does nothing. Cross a bean boundary, or the annotation
> is decoration.

### 2.6 Concurrency limiting

```bash
for i in $(seq 6); do curl -s -X POST "localhost:8080/api/stock/$PID/recount" & done; wait
```

`StockFacade.expensiveRecount` is `@ConcurrencyLimit(2)` — a bulkhead. At most two threads inside at
once, the rest queue. One slow dependency can no longer consume the whole thread pool.

---

## 3. Locking

Four strategies, one endpoint to compare them: `POST /api/stock/demo/race`.

```bash
for MODE in unlocked redis-lock row-lock optimistic; do
  echo "== $MODE"
  curl -s -X POST "localhost:8080/api/stock/demo/race?productId=$PID&mode=$MODE&threads=20&quantityEach=1&initialStock=10"
  echo
done
```

20 virtual threads, released together by a `CountDownLatch`, each reserving 1 unit from a stock of 10.
Correct behaviour: exactly 10 succeed, 10 fail, `oversold: 0`.

### 3.1 `unlocked` — the bug

Read-modify-write with no protection. Every thread reads `stockQty = 10`, all 20 decide there is
enough, and you ship 20 units you do not own. `oversold > 0`.

This is the baseline. Keep it in the project; a race condition you have watched happen is worth ten
you have read about.

### 3.2 `row-lock` — pessimistic database lock

`ProductRepository.findByIdForUpdate` is `@Lock(PESSIMISTIC_WRITE)` — `SELECT ... FOR UPDATE`. The
database serialises the readers; correctness is not in question.

* `@QueryHints(jakarta.persistence.lock.timeout = 5000)` bounds the wait; without a timeout, a
  deadlock is a hang.
* It does not scale past one database, and it holds a connection for the whole critical section.

### 3.3 `optimistic` — `@Version` + retry

`Product.version` is `@Version`. Hibernate appends `WHERE id = ? AND version = ?` to every update; a
zero-row update means somebody else won, and Hibernate throws `ObjectOptimisticLockingFailureException`.

`StockFacade.adjustStock` retries that exception with backoff. Two details that matter:

* `StockService.applyDeltaOptimistic` is `@Transactional(propagation = REQUIRES_NEW)` so **each retry
  gets a fresh transaction**. Retrying inside a transaction that is already marked rollback-only just
  produces the same failure four more times.
* The `attempts` field in the response shows how many tries it took — raise `threads` and watch it grow.

Optimistic locking is the right default when conflicts are rare. When `attempts` is regularly at the
limit, the row is too hot for it — move to §3.2 or §3.4.

### 3.4 `redis-lock` — the distributed lock

The one you cannot get from the database, because it spans instances.

| File | Role |
|---|---|
| `common/lock/DistributedLock.java` | the annotation: SpEL `key()`, `waitTimeMs()`, `leaseTimeMs()`, `throwOnFailure()` |
| `common/lock/RedisLockService.java` | acquire / release / `runLocked` |
| `common/lock/DistributedLockAspect.java` | the `@Around` aspect |

**Acquire** is a single atomic command:

```
SET inventory:lock:stock:<id> <random-token> NX PX <lease>
```

`NX` = only if absent. One round trip, no check-then-act gap.

**Release** is a Lua script, not a `DEL`:

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end
```

Compare-and-delete, atomic. A plain `DEL` deletes **whoever holds the lock now** — if your lease
expired mid-work, that is somebody else's lock, and you have just handed the critical section to two
threads at once.

**The lease TTL is the deadlock cure.** If the JVM holding the lock is killed, no `finally` block ever
runs; Redis expiring the key is what lets the system recover on its own. The trade-off is real: too
short and you release a lock while still working (which is why the token check above exists), too long
and a crash blocks everyone for that long.

Watch a lock live:

```bash
curl -s -X POST "localhost:8080/api/stock/$PID/audit" &     # 30s lease
curl -s localhost:8080/api/admin/locks                       # key, owner token, remaining TTL
```

Call `/audit` twice at once (`waitTimeMs = 0`) and the second gets **409 LOCK_BUSY** immediately —
`fail fast` instead of `queue up` is the right choice for a report nobody is waiting on.

### 3.5 Lock ordering and deadlock

```bash
curl -s -X POST localhost:8080/api/stock/transfer -H 'Content-Type: application/json' \
  -d "{\"fromProductId\":\"$PID\",\"toProductId\":\"$OTHER\",\"quantity\":5}"
```

A transfer locks two products. If thread A locks `X` then `Y` while thread B locks `Y` then `X`, they
wait for each other forever. `StockFacade.transfer` **sorts the ids** before locking, so every thread
in the system takes the locks in the same order and a cycle is impossible.

Same rule applies to the database rows in `StockService.transfer`. Deterministic ordering is the
cheapest deadlock prevention there is.

### 3.6 Lock outside transaction

`DistributedLockAspect` is `@Order(Ordered.LOWEST_PRECEDENCE - 100)`, which puts it **outside** the
transaction advice:

```
acquire lock → BEGIN → work → COMMIT → release lock
```

Get this backwards and you release the lock before the commit is visible to other nodes — a window in
which the next lock holder reads stale data and the whole exercise was pointless.

This ordering is also why `StockFacade` (locks, retries) and `StockService` (transactions) are two
beans: it makes the boundary something you can see, instead of something you hope the proxy got right.

---

## 4. Error mapping

`common/exception/GlobalExceptionHandler.java`:

| Exception | Status | Code |
|---|---|---|
| `LockAcquisitionException` | 409 | `LOCK_BUSY` |
| `OptimisticLockingFailureException` | 409 | `CONCURRENT_MODIFICATION` |
| `CannotAcquireLockException` | 409 | `ROW_LOCK_TIMEOUT` |
| `SupplierUnavailableException` | 503 | `SUPPLIER_UNAVAILABLE` |

All concurrency failures are **409, never 500**. A 500 says "my bug, do not retry". A 409 says
"transient, retrying is safe" — and that is the difference between a client that recovers and one
that gives up.

---

## 5. Endpoint index

**Caching**
```
GET    /api/products?page=&size=                 uncached baseline
GET    /api/products/cached?page=&size=          @Cacheable + DTO, not Page
GET    /api/products/by-sku/{sku}                caches nulls (penetration)
GET    /api/catalog/report                       sync = true (stampede)
GET    /api/catalog/top-products?limit=          manual cache-aside + TTL jitter
DELETE /api/catalog/top-products?limit=
GET    /api/catalog/search?q=                    condition + unless
```

**Retry**
```
GET    /api/restock/quote/{sku}?failurePercent=           backoff + jitter
GET    /api/restock/quote/{sku}/budgeted?failurePercent=  timeout budget
GET    /api/restock/quote/{sku}/cached?failurePercent=    cache outside, retry inside
POST   /api/restock/{productId}?quantity=&failurePercent= hand-written fallback
PATCH  /api/stock/{id}/adjust                             retry on optimistic conflict
POST   /api/stock/{id}/recount                            @ConcurrencyLimit(2)
```

**Locking**
```
POST   /api/stock/{id}/reserve             @DistributedLock
POST   /api/stock/{id}/reserve-row-lock    PESSIMISTIC_WRITE
POST   /api/stock/{id}/reserve-unsafe      the bug, on purpose
POST   /api/stock/transfer                 two locks, sorted order
POST   /api/stock/{id}/audit               waitTimeMs = 0, fail fast
POST   /api/stock/demo/race?mode=          compare all four
```

**Introspection**
```
GET    /api/admin/cache
GET    /api/admin/cache/{name}/keys
GET    /api/admin/cache/{name}/entry?key=
DELETE /api/admin/cache/{name}
DELETE /api/admin/cache/{name}/entry?key=
GET    /api/admin/locks
```

---

## 6. Things that will bite you

1. **Self-invocation.** `this.cachedMethod()` skips the proxy. Every proxy-based annotation, no exception.
2. **`PageImpl` in a cache.** No stable serialized form. Cache a DTO.
3. **Default typing without an allow-list.** `RedisConfig.redisTypeValidator` restricts deserialization
   to known packages. Without it, anyone who can write to Redis can instantiate arbitrary classes in
   your JVM — a remote code execution, not a theoretical one.
4. **`KEYS` in production.** Blocks the single Redis thread for the whole sweep. `AdminCacheController`
   uses `SCAN`.
5. **`DEL` to release a lock.** Deletes whoever holds it *now*. Compare-and-delete with your own token.
6. **No lock TTL.** One crashed JVM deadlocks the cluster permanently.
7. **Retrying inside the failed transaction.** Use `REQUIRES_NEW` so each attempt is genuinely new.
8. **Retrying non-idempotent writes** without an idempotency key. That is how customers get charged twice.
9. **A cache that can take the site down.** Fail open. `CacheConfig.errorHandler` logs and falls through
   to the database.
10. **Every key expiring at the same second.** Jitter the TTLs.

---

## 7. Suggested reading order

1. `config/CacheConfig.java` and `config/RedisConfig.java` — the wiring
2. `product/ProductService.java` — `@Cacheable` / `@CachePut` / `@CacheEvict` in a normal CRUD service
3. `catalog/CatalogService.java` — `sync`, `condition`, `unless`, manual cache-aside
4. `supplier/SupplierGateway.java` then `supplier/RestockService.java` — retry, then fallback
5. `common/lock/*` — annotation, service, aspect
6. `stock/StockService.java` and `stock/StockFacade.java` — the two-bean split
7. `stock/StockRaceDemoService.java` — all four strategies, measured
