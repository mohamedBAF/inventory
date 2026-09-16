package com.yourname.inventory.order.dto;

import com.yourname.inventory.order.Order;
import com.yourname.inventory.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        OrderStatus status,
        BigDecimal total,
        List<OrderItemResponse> items,
        LocalDateTime createdAt
) {
    public record OrderItemResponse(
            UUID productId,
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {}

    public static OrderResponse from(Order o) {
        List<OrderItemResponse> items = o.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProduct().getId(),
                        i.getProduct().getName(),
                        i.getQuantity(),
                        i.getUnitPrice()
                ))
                .toList();

        return new OrderResponse(o.getId(), o.getStatus(), o.getTotal(), items, o.getCreatedAt());
    }
}
