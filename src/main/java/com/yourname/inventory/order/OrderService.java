package com.yourname.inventory.order;

import com.yourname.inventory.order.dto.CreateOrderRequest;
import com.yourname.inventory.order.dto.OrderResponse;
import com.yourname.inventory.product.Product;
import com.yourname.inventory.product.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Transactional
    public OrderResponse create(CreateOrderRequest req) {
        Order order = new Order();

        for (CreateOrderRequest.OrderItemRequest itemReq : req.items()) {
            Product product = productRepository.findById(itemReq.productId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Product not found: " + itemReq.productId()));

            if (product.getStockQty() < itemReq.quantity())
                throw new IllegalStateException(
                        "Insufficient stock for product: " + product.getSku());

            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(product.getPrice());
            product.setStockQty(product.getStockQty() - itemReq.quantity());

            order.addItem(item);
        }

        BigDecimal total = order.getItems().stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setTotal(total);
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id) {
        return orderRepository.findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + id));
    }

    @Transactional
    public OrderResponse updateStatus(UUID id, OrderStatus status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + id));
        order.setStatus(status);
        return OrderResponse.from(orderRepository.save(order));
    }
}
