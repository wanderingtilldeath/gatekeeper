package com.gatekeeper.svc_java.event;

public record OrderPlaced(String orderId, String user, String sku, int qty, String requestId) {
    public static final String EXCHANGE = "orders";
    public static final String ROUTING_KEY = "order.placed";
}
