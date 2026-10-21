package com.gatekeeper.svc_java.controller;

import com.gatekeeper.svc_java.event.OrderPlaced;
import com.gatekeeper.svc_java.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/java/orders")
public class OrderController {

    public record OrderRequest(String sku, int qty) {}

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderPlaced place(@RequestBody OrderRequest req,
                             @RequestHeader(value = "X-User", required = false) String user,
                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (user == null) { // only the gateway sets X-User; direct calls are rejected
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (req.sku() == null || req.sku().isBlank() || req.qty() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sku and positive qty required");
        }
        return orders.place(user, req.sku(), req.qty(), requestId != null ? requestId : UUID.randomUUID().toString());
    }

    @GetMapping("/{id}")
    public OrderPlaced get(@PathVariable String id) {
        OrderPlaced order = orders.find(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return order;
    }
}
