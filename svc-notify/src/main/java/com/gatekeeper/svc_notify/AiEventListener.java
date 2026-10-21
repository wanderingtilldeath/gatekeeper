package com.gatekeeper.svc_notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AiEventListener {
    private static final Logger log = LoggerFactory.getLogger(AiEventListener.class);

    /** Own type on purpose: services share the JSON contract, not a class. */
    record AiEvent(String kind, String user, String detail) {}

    @RabbitListener(queues = "ai.notify")
    void onAiEvent(AiEvent e) {
        log.warn("[ALERT {}] {}: {}", e.kind(), e.user(), e.detail());
    }
}
