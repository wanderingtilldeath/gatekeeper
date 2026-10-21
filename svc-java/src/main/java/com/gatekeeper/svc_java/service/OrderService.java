package com.gatekeeper.svc_java.service;

import com.gatekeeper.svc_java.client.InventoryClient;
import com.gatekeeper.svc_java.client.InventoryClient.ReserveRequest;
import com.gatekeeper.svc_java.event.OrderPlaced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final Map<String, OrderPlaced> orders = new ConcurrentHashMap<>();
    private final InventoryClient inventory;
    private final RabbitTemplate rabbit;

    public OrderService(InventoryClient inventory, RabbitTemplate rabbit) {
        this.inventory = inventory;
        this.rabbit = rabbit;
    }

    public OrderPlaced place(String user, String sku, int qty, String requestId) {
        boolean reserved;
        try {
            reserved = inventory.reserve(sku, new ReserveRequest(qty), requestId).reserved();
        } catch (Exception e) { // timeout, connection refused, or open circuit: fail fast, store nothing
            log.warn("[{}] inventory unavailable: {}", requestId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "inventory unavailable");
        }
        if (!reserved) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "out of stock");
        }

        OrderPlaced order = new OrderPlaced(UUID.randomUUID().toString(), user, sku, qty, requestId);
        orders.put(order.orderId(), order);
        try {
            rabbit.convertAndSend(OrderPlaced.EXCHANGE, OrderPlaced.ROUTING_KEY, order);
        } catch (Exception e) { // shortcut: no outbox, a lost event is logged, not retried; upgrade to transactional outbox if events become critical
            log.error("[{}] order {} saved but event not published", requestId, order.orderId(), e);
        }
        return order;
    }

    public OrderPlaced find(String id) {
        return orders.get(id);
    }
}
