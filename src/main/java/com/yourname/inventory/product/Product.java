package com.yourname.inventory.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "product")
@Getter @Setter @NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String sku;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stockQty;

    /**
     * LESSON 5 - OPTIMISTIC locking, the cheapest lock there is.
     *
     * Hibernate adds "WHERE id = ? AND version = ?" to every UPDATE and bumps the number.
     * If another transaction already bumped it, zero rows match and you get an
     * ObjectOptimisticLockingFailureException instead of silently overwriting their work
     * (a "lost update").
     *
     * No database row is ever blocked, so it is free under low contention - the price is that
     * the loser must RETRY. That is exactly why @Retryable and @Version are taught together:
     * see StockFacade#adjustStock.
     */
    @Version
    private long version;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
