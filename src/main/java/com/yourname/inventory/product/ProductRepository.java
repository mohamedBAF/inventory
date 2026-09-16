package com.yourname.inventory.product;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    @Query("SELECT p FROM Product p WHERE p.stockQty > :min")
    List<Product> findInStock(@Param("min") int min);

    /**
     * LESSON 6 - PESSIMISTIC locking, enforced by the database itself.
     *
     * This issues "SELECT ... FOR UPDATE": the row is locked until the transaction commits, and
     * any other transaction asking for the same row WAITS. No retry loop, no lost updates -
     * the price is that you are holding a database lock, so the transaction must stay short.
     *
     * Optimistic vs pessimistic, the short version:
     *   - low contention / short critical section  -> @Version + retry (optimistic)
     *   - high contention / you must not fail      -> FOR UPDATE (pessimistic)
     *   - work spanning several services, or no single DB row to lock -> Redis distributed lock
     *
     * MUST be called inside a @Transactional method; without a transaction there is nothing to
     * hold the lock and Hibernate throws.
     *
     * The lock timeout stops one stuck transaction from parking every other request forever;
     * on PostgreSQL exceeding it surfaces as a CannotAcquireLockException.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /** Feeds the expensive catalog report that we cache (see CatalogService). */
    @Query("SELECT p FROM Product p ORDER BY p.stockQty DESC")
    List<Product> findTopByStock(Pageable pageable);

    @Query("""
            SELECT p FROM Product p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%'))
               OR LOWER(p.sku)  LIKE LOWER(CONCAT('%', :term, '%'))
            """)
    List<Product> search(@Param("term") String term);
}
