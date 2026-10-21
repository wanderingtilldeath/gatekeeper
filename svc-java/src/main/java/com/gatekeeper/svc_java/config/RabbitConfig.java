package com.gatekeeper.svc_java.config;

import com.gatekeeper.svc_java.event.OrderPlaced;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Producer owns the exchange; the consumer (svc-notify) owns its queue, so start svc-notify once before ordering. */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange ordersExchange() {
        return new TopicExchange(OrderPlaced.EXCHANGE);
    }

    @Bean
    MessageConverter jsonConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
