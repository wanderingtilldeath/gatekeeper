package com.gatekeeper.svc_ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class Events {
    private static final Logger log = LoggerFactory.getLogger(Events.class);
    private final RabbitTemplate rabbit;

    Events(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    /** Alerts are best effort: failure is logged, never fails the request (no outbox). */
    void publish(String kind, String user, String detail) {
        try {
            rabbit.convertAndSend(AiEvent.EXCHANGE, AiEvent.ROUTING_KEY, new AiEvent(kind, user, detail));
        } catch (Exception e) {
            log.warn("could not publish {} event: {}", kind, e.getMessage());
        }
    }
}
