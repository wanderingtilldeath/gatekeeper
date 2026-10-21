package com.gatekeeper.svc_notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderListener {
    private static final Logger log = LoggerFactory.getLogger(OrderListener.class);

    /** Own type on purpose: services share the JSON contract, not a class. */
    record OrderPlaced(String orderId, String user, String sku, int qty, String requestId) {}

    @RabbitListener(queues = RabbitConfig.QUEUE)
    void onOrderPlaced(OrderPlaced e) {
        if (e.user() == null) { // malformed event: throw so it retries, then lands in the DLQ
            throw new IllegalArgumentException("order event without user: " + e.orderId());
        }
        log.info("[{}] email sent to {}: order {} ({} x {}) confirmed", e.requestId(), e.user(), e.orderId(), e.qty(), e.sku());
    }
}
